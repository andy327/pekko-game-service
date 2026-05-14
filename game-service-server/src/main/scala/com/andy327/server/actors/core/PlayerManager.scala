package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.ws.Message

import com.andy327.model.core.PlayerId
import com.andy327.server.lobby.Player

/** A child actor of GameManager that tracks one PlayerActor per connected player.
  *
  * On reconnect (same PlayerId), the old PlayerActor is stopped and replaced with a new one. A monotonically increasing
  * `spawnCount` is appended to each child name to avoid Pekko's uniqueness constraint during the brief window while the
  * old actor is still stopping.
  *
  * Actor relationships:
  *   - Parent: [[GameManager]]
  *   - Children: [[PlayerActor]] (one per currently-connected player)
  *   - Receives from: [[GameManager]] (`RegisterPlayer`, `LookupPlayer`, `PlayerDisconnected`)
  *   - Sends to: [[PlayerActor]] (`Disconnect` on explicit disconnect or reconnect)
  */
object PlayerManager {
  sealed trait Command

  final case class RegisterPlayer(
      player: Player,
      wsOut: ActorRef[Message],
      replyTo: ActorRef[ActorRef[PlayerActor.Command]]
  ) extends Command
  final case class LookupPlayer(playerId: PlayerId, replyTo: ActorRef[Option[ActorRef[PlayerActor.Command]]])
      extends Command
  final case class PlayerDisconnected(playerId: PlayerId) extends Command

  def apply(): Behavior[Command] = running(Map.empty, 0)

  private def running(players: Map[PlayerId, ActorRef[PlayerActor.Command]], spawnCount: Int): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case RegisterPlayer(player, wsOut, replyTo) =>
          players.get(player.id).foreach(context.stop)
          val ref = context.spawn(PlayerActor(player, wsOut), s"player-${player.id}-$spawnCount")
          replyTo ! ref
          running(players + (player.id -> ref), spawnCount + 1)

        case LookupPlayer(playerId, replyTo) =>
          replyTo ! players.get(playerId)
          Behaviors.same

        case PlayerDisconnected(playerId) =>
          players.get(playerId).foreach(_ ! PlayerActor.Disconnect)
          running(players - playerId, spawnCount)
      }
    }
}
