package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.OverflowStrategy
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.util.Timeout

import com.andy327.server.actors.core.{GameManager, PlayerActor}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.lobby.Player

/** HTTP route that upgrades connections to WebSocket sessions.
  *
  * On connect the client must supply a valid Bearer token (obtained via POST /auth/token). The server materializes an
  * ActorSource-backed stream, spawns a PlayerActor wired to it via GameManager.RegisterPlayer, and begins forwarding
  * push events (lobby updates, game state, game-end notifications) as JSON TextMessages. Inbound client messages are
  * currently discarded. When the WebSocket closes, PlayerDisconnected is sent to GameManager so that the associated
  * PlayerActor is stopped; conversely, when the PlayerActor stops (explicit disconnect or replacement on reconnect),
  * it completes the stream via `PlayerActor.WsComplete`, closing the WebSocket from the server side.
  *
  * Route: GET /ws (Auth: Bearer token required)
  *
  * Actor relationships:
  *   - Sends to: `GameManager` (`RegisterPlayer` on connect, `PlayerDisconnected` on close)
  */
class WebSocketRoutes(gameManager: ActorSystem[GameManager.Command]) {
  implicit private val system: ActorSystem[GameManager.Command] = gameManager
  implicit private val timeout: Timeout = Timeout(5.seconds)
  private val ec = system.executionContext

  val routes: Route = path("ws") {
    authenticatePlayer { player =>
      handleWebSocketMessages(buildFlow(player))
    }
  }

  /** Builds a WebSocket Flow for the given authenticated player.
    *
    * Pre-materializes an ActorSource over [[PlayerActor.WsOutput]] to obtain a `wsOut` ref before the stream starts,
    * then asks GameManager to register the player (which spawns a PlayerActor bound to that ref). The outbound side of
    * the flow carries server-push events; the inbound side is drained with Sink.ignore. The stream completes when the
    * PlayerActor emits [[PlayerActor.WsComplete]]. When the stream terminates (WebSocket closed by either side),
    * PlayerDisconnected — carrying this session's PlayerActor ref — is sent to GameManager so a stale close cannot
    * tear down a newer session for the same player.
    */
  private def buildFlow(player: Player): Flow[Message, Message, Any] = {
    // Materialize the ActorRef up front so we can pass it to RegisterPlayer before the stream runs
    val (wsOut, source) = ActorSource
      .actorRef[PlayerActor.WsOutput](
        completionMatcher = { case PlayerActor.WsComplete => },
        failureMatcher = PartialFunction.empty,
        bufferSize = 16,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .preMaterialize()

    // Spawn a PlayerActor wired to wsOut; the ref identifies this session in the disconnect notification below
    val playerRefFuture = gameManager ? ((replyTo: ActorRef[ActorRef[PlayerActor.Command]]) =>
      GameManager.RegisterPlayer(player, wsOut, replyTo)
    )

    // On WebSocket close (either side), stop this session's PlayerActor via GameManager
    val outbound = source
      .collect { case PlayerActor.WsMessage(message) => message }
      .watchTermination() { (_, done) =>
        done.onComplete { _ =>
          playerRefFuture.foreach(ref => gameManager ! GameManager.PlayerDisconnected(player.id, ref))(ec)
        }(ec)
      }

    Flow.fromSinkAndSource(Sink.ignore, outbound)
  }
}
