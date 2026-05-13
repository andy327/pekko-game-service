package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.Behavior
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import com.andy327.server.lobby.Player

/**
 * An actor representing an individual player's session.
 *
 * Receives push events from LobbyManager and game actors. In this commit the WebSocket sink is not yet wired; the
 * next commit adds the wsOut ref and serializes events to JSON for delivery to the client.
 *
 * Actor relationships:
 *   - Parent: [[PlayerManager]]
 *   - Receives from: [[LobbyManager]] and game actors (fan-out `SendEvent`), [[PlayerManager]] (`Disconnect`)
 *   - Sends to: WebSocket client (not yet wired; planned for Issue #8)
 */
object PlayerActor {
  sealed trait Command
  final case class SendEvent(event: PlayerEvent) extends Command
  case object Disconnect extends Command

  def apply(player: Player): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.info(s"PlayerActor started for player ${player.name} (${player.id})")
      Behaviors.receiveMessage {
        case SendEvent(_) =>
          Behaviors.same
        case Disconnect =>
          Behaviors.stopped
      }
    }
}
