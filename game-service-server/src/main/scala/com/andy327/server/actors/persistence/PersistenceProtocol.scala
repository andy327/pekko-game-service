package com.andy327.server.actors.persistence

import io.circe.Json
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}

/** Commands and reply types for the persistence actor (e.g. [[PostgresActor]]).
  *
  * Senders fire a `SaveSnapshot` command with a typed `replyTo` ref and receive a `SnapshotSaved` reply carrying an
  * `Either` that distinguishes success from failure. `AppendMove` is fire-and-forget (no reply). Loads do not go
  * through the actor: startup restore and completed-game reads call the repository directly.
  */
object PersistenceProtocol {

  /** Super-type for all messages sent to a persistence actor. */
  trait Command

  // --- Commands (sent to PostgresActor) ---

  /** Persist (insert or update) the full game snapshot; replies with [[SnapshotSaved]]. */
  final case class SaveSnapshot(
      gameId: GameId,
      gameType: GameType,
      game: Game[_, _, _, _, _],
      replyTo: ActorRef[SnapshotSaved]
  ) extends Command

  /** Append one move to the game's history log at ordinal `seq`.
    *
    * Fire-and-forget: there is no reply and a failed append is logged but not retried. The tradeoff is that a dropped
    * append leaves a gap in the history log while the game snapshot still reflects true current state — the game stays
    * correct, only its recorded history is lossy. If stronger guarantees are wanted later, this is the seam to add an
    * acknowledged, retried append (TODO: ack-and-retry; deferred while history is non-critical).
    */
  final case class AppendMove(gameId: GameId, seq: Int, playerId: PlayerId, move: Json) extends Command

  // --- Replies (sent back to the requester) ---

  /** Reply to [[SaveSnapshot]]; `Right(())` on success, `Left` on error. */
  final case class SnapshotSaved(result: Either[Throwable, Unit])
}
