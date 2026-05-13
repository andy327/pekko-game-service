package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.PlayerId
import com.andy327.server.lobby.Player

/**
 * A child actor of GameManager that tracks one PlayerActor per connected player.
 *
 * On reconnect (same PlayerId), the old PlayerActor is stopped and replaced with a new one.
 */
object PlayerManager {
  sealed trait Command

  final case class RegisterPlayer(player: Player, replyTo: ActorRef[ActorRef[PlayerActor.Command]]) extends Command
  final case class LookupPlayer(playerId: PlayerId, replyTo: ActorRef[Option[ActorRef[PlayerActor.Command]]])
      extends Command
  final case class PlayerDisconnected(playerId: PlayerId) extends Command

  def apply(): Behavior[Command] = running(Map.empty, 0)

  private def running(players: Map[PlayerId, ActorRef[PlayerActor.Command]], spawnCount: Int): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case RegisterPlayer(player, replyTo) =>
          players.get(player.id).foreach(context.stop)
          val ref = context.spawn(PlayerActor(player), s"player-${player.id}-$spawnCount")
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
