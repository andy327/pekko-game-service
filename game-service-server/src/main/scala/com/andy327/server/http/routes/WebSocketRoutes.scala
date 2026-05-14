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
  * PlayerActor is stopped.
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
    * Pre-materializes an ActorSource to obtain a `wsOut: ActorRef[Message]` before the stream starts, then asks
    * GameManager to register the player (which spawns a PlayerActor bound to that ref). The outbound side of the flow
    * carries server-push events; the inbound side is drained with Sink.ignore. When the stream terminates (WebSocket
    * closed by either side), PlayerDisconnected is sent to GameManager to clean up the PlayerActor.
    */
  private def buildFlow(player: Player): Flow[Message, Message, Any] = {
    // Materialize the ActorRef up front so we can pass it to RegisterPlayer before the stream runs
    val (wsOut, source) = ActorSource
      .actorRef[Message](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = 16,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .preMaterialize()

    // Spawn a PlayerActor wired to wsOut — reply is discarded since wsOut is all we need here
    (gameManager ? ((replyTo: ActorRef[ActorRef[PlayerActor.Command]]) =>
      GameManager.RegisterPlayer(player, wsOut, replyTo)
    )).foreach(_ => ())(ec)

    // On WebSocket close (either side), stop the PlayerActor via GameManager
    val outbound = source.watchTermination() { (_, done) =>
      done.onComplete(_ => gameManager ! GameManager.PlayerDisconnected(player.id))(ec)
    }

    Flow.fromSinkAndSource(Sink.ignore, outbound)
  }
}
