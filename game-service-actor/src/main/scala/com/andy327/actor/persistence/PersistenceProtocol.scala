package com.andy327.actor.persistence

import io.circe.Json
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.{Game, GameType, MatchId, PlayerId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

/** Commands and reply types for the persistence actor (e.g. [[PostgresActor]]).
  *
  * Senders fire a `SaveSnapshot` command with a typed `replyTo` ref and receive a `SnapshotSaved` reply carrying an
  * `Either` that distinguishes success from failure. `AppendMove` and `RecordGameResult` are fire-and-forget (no
  * reply). Loads do not go through the actor: startup restore and completed-game reads call the repository directly.
  */
object PersistenceProtocol {

  /** Super-type for all messages sent to a persistence actor. */
  trait Command

  // --- Commands (sent to PostgresActor) ---

  /** Persist (insert or update) the full game snapshot; replies with [[SnapshotSaved]]. */
  final case class SaveSnapshot(
      matchId: MatchId,
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
  final case class AppendMove(matchId: MatchId, seq: Int, playerId: PlayerId, move: Json) extends Command

  /** Record one participant's outcome for a completed game in their durable history.
    *
    * Fire-and-forget like [[AppendMove]]: emitted once per participant when a game reaches a terminal state, with no
    * reply and no retry. A dropped record leaves a gap in that player's history while the game itself stays correct.
    */
  final case class RecordGameResult(
      playerId: PlayerId,
      matchId: MatchId,
      gameType: GameType,
      result: GameResult,
      forfeit: Boolean
  ) extends Command

  // --- Replies (sent back to the requester) ---

  /** Reply to [[SaveSnapshot]]; `Right(())` on success, `Left` on error. */
  final case class SnapshotSaved(result: Either[Throwable, Unit])
}
