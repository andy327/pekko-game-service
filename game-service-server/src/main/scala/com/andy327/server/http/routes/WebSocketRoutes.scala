package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.slf4j.LoggerFactory

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.util.Timeout

import com.andy327.actor.core.{GameManager, PlayerActor, PlayerEvent}
import com.andy327.actor.lobby.Player
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.ClientMessage
import com.andy327.server.http.json.JsonProtocol._

/** HTTP route that upgrades connections to WebSocket sessions.
  *
  * On connect the client must supply a valid Bearer token (obtained via POST /auth/token). The server materializes an
  * ActorSource-backed stream, spawns a PlayerActor wired to it via GameManager.RegisterPlayer, and begins forwarding
  * push events (lobby updates, game state, game-end notifications, chat) as JSON TextMessages. Inbound client frames
  * are decoded as [[json.ClientMessage]] and routed to GameManager (e.g. a `ChatSend` becomes `GameManager.SendChat`);
  * unparseable frames are logged and dropped. When the WebSocket closes, PlayerDisconnected is sent to GameManager so
  * the associated PlayerActor is stopped; conversely, when the PlayerActor stops (explicit disconnect or replacement on
  * reconnect), it completes the stream via `PlayerActor.SessionComplete`, closing the WebSocket from the server side.
  *
  * Route: GET /ws (Auth: Bearer token required)
  *
  * Actor relationships:
  *   - Sends to: `GameManager` (`RegisterPlayer` on connect, `SendChat` on an inbound chat frame, `PlayerDisconnected`
  *     on close)
  */
class WebSocketRoutes(gameManager: ActorSystem[GameManager.Command]) {
  implicit private val system: ActorSystem[GameManager.Command] = gameManager
  implicit private val timeout: Timeout = Timeout(5.seconds)
  implicit private val mat: Materializer = Materializer(system)
  private val ec = system.executionContext
  private val logger = LoggerFactory.getLogger(getClass)

  val routes: Route = path("ws") {
    authenticatePlayer { player =>
      handleWebSocketMessages(buildFlow(player))
    }
  }

  /** Render an outbound domain event as a tagged JSON string for delivery to the client. */
  private def render(event: PlayerEvent): String = event.asJson.deepDropNullValues.noSpaces

  /** Decode one inbound client frame and act on it; malformed frames are logged and ignored. */
  private def handleInbound(player: Player)(text: String): Unit =
    decode[ClientMessage](text) match {
      case Right(ClientMessage.ChatSend(gameId, body)) =>
        gameManager ! GameManager.SendChat(gameId, player, body)
      case Left(err) =>
        logger.warn(s"Ignoring unparseable client message from ${player.id}: ${err.getMessage}")
    }

  /** Builds a WebSocket Flow for the given authenticated player.
    *
    * Pre-materializes an ActorSource over [[PlayerActor.SessionOutput]] to obtain a `wsOut` ref before the stream
    * starts, then asks GameManager to register the player (which spawns a PlayerActor bound to that ref). The inbound
    * side decodes each text frame into a [[ClientMessage]] and routes it; the outbound side renders each server-push
    * event to JSON. The stream completes when the PlayerActor emits [[PlayerActor.SessionComplete]]. When the stream
    * terminates (WebSocket
    * closed by either side), PlayerDisconnected — carrying this session's PlayerActor ref — is sent to GameManager so a
    * stale close cannot tear down a newer session for the same player.
    */
  private def buildFlow(player: Player): Flow[Message, Message, Any] = {
    // Materialize the ActorRef up front so we can pass it to RegisterPlayer before the stream runs
    val (wsOut, source) = ActorSource
      .actorRef[PlayerActor.SessionOutput](
        completionMatcher = { case PlayerActor.SessionComplete => },
        failureMatcher = PartialFunction.empty,
        bufferSize = 16,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .preMaterialize()

    // Spawn a PlayerActor wired to wsOut; the ref identifies this session in the disconnect notification below
    val playerRefFuture = gameManager ? ((replyTo: ActorRef[ActorRef[PlayerActor.Command]]) =>
      GameManager.RegisterPlayer(player, wsOut, replyTo)
    )

    // Inbound: decode each text frame and route it (binary frames are ignored)
    val inbound: Sink[Message, Any] =
      Flow[Message]
        .collect { case tm: TextMessage => tm }
        .mapAsync(1)(_.toStrict(timeout.duration).map(_.text)(ec))
        .to(Sink.foreach(handleInbound(player)))

    // Render each domain event to a JSON text frame at the transport edge; on WebSocket close (either side),
    // stop this session's PlayerActor via GameManager
    val outbound = source
      .collect { case PlayerActor.SessionEvent(event) => TextMessage(render(event)) }
      .watchTermination() { (_, done) =>
        done.onComplete { _ =>
          playerRefFuture.foreach(ref => gameManager ! GameManager.PlayerDisconnected(player.id, ref))(ec)
        }(ec)
      }

    Flow.fromSinkAndSource(inbound, outbound)
  }
}
