package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}

import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.Player

/** An actor representing an individual player's session.
  *
  * Receives push events from LobbyManager and game actors, serializes them to JSON, and forwards them to the WebSocket
  * sink via the `wsOut` ActorRef. Each event is encoded as a tagged JSON object so the client can dispatch on the
  * `type` field.
  *
  * Actor relationships:
  *   - Parent: [[PlayerManager]]
  *   - Receives from: [[LobbyManager]] and game actors (fan-out `SendEvent`), [[PlayerManager]] (`Disconnect`)
  *   - Sends to: WebSocket client via `wsOut` (`TextMessage`)
  */
object PlayerActor {
  sealed trait Command

  /** Serialize `event` to JSON and forward it to the player's WebSocket sink. */
  final case class SendEvent(event: PlayerEvent) extends Command

  /** Terminate this actor and close the associated WebSocket stream. */
  case object Disconnect extends Command

  def apply(player: Player, wsOut: ActorRef[Message]): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.info(s"PlayerActor started for player ${player.name} (${player.id})")
      Behaviors.receiveMessage {
        case SendEvent(event) =>
          val json = playerEventFormat.write(event).compactPrint
          wsOut ! TextMessage(json)
          Behaviors.same
        case Disconnect =>
          Behaviors.stopped
      }
    }
}
