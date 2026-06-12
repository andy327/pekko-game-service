package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.PlayerId
import com.andy327.server.lobby.Player

/** A child actor of GameManager that tracks one PlayerActor per connected player.
  *
  * On reconnect (same PlayerId), the old PlayerActor is sent `Disconnect` — which completes its WebSocket stream and
  * stops it — and is replaced with a new one. A monotonically increasing `spawnCount` is appended to each child name
  * to avoid Pekko's uniqueness constraint during the brief window while the old actor is still stopping.
  *
  * `PlayerDisconnected` carries the actor ref of the session that terminated so that a close notification from an old,
  * already-replaced WebSocket stream cannot tear down a newer session for the same player.
  *
  * Actor relationships:
  *   - Parent: [[GameManager]]
  *   - Children: [[PlayerActor]] (one per currently-connected player)
  *   - Receives from: [[GameManager]] (`RegisterPlayer`, `LookupPlayer`, `PlayerDisconnected`)
  *   - Sends to: [[PlayerActor]] (`Disconnect` on explicit disconnect or reconnect)
  */
object PlayerManager {
  sealed trait Command

  /** Spawn (or replace) a PlayerActor for `player`, wired to `wsOut`; replies with the new actor ref. */
  final case class RegisterPlayer(
      player: Player,
      wsOut: ActorRef[PlayerActor.WsOutput],
      replyTo: ActorRef[ActorRef[PlayerActor.Command]]
  ) extends Command

  /** Look up the live PlayerActor for `playerId`; replies `None` if the player is not connected. */
  final case class LookupPlayer(playerId: PlayerId, replyTo: ActorRef[Option[ActorRef[PlayerActor.Command]]])
      extends Command

  /** Stop the PlayerActor for `playerId` and remove it from the registry.
    *
    * Ignored unless `playerRef` is still the registered actor for that player, so a stale notification from a
    * replaced session cannot disconnect the current one.
    */
  final case class PlayerDisconnected(playerId: PlayerId, playerRef: ActorRef[PlayerActor.Command]) extends Command

  def apply(): Behavior[Command] = running(Map.empty, 0)

  /** Tracks the live player registry.
    *
    * @param players map from PlayerId to the currently running PlayerActor ref
    * @param spawnCount monotonically increasing counter appended to child names to satisfy Pekko's uniqueness
    *                   constraint during the brief overlap when an old actor is still stopping on reconnect
    */
  private def running(players: Map[PlayerId, ActorRef[PlayerActor.Command]], spawnCount: Int): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case RegisterPlayer(player, wsOut, replyTo) =>
          players.get(player.id).foreach(_ ! PlayerActor.Disconnect)
          val ref = context.spawn(PlayerActor(player, wsOut), s"player-${player.id}-$spawnCount")
          replyTo ! ref
          running(players + (player.id -> ref), spawnCount + 1)

        case LookupPlayer(playerId, replyTo) =>
          replyTo ! players.get(playerId)
          Behaviors.same

        case PlayerDisconnected(playerId, playerRef) =>
          if (players.get(playerId).contains(playerRef)) {
            playerRef ! PlayerActor.Disconnect
            running(players - playerId, spawnCount)
          } else {
            context.log.debug(s"Ignoring stale PlayerDisconnected for $playerId")
            Behaviors.same
          }
      }
    }
}
