package com.andy327.server.actors.persistence

import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.{Game, GameId, GameType}

/** Commands and reply types for the persistence actor (e.g. [[PostgresActor]]).
  *
  * Senders fire a `LoadSnapshot` or `SaveSnapshot` command with a typed `replyTo` ref and receive a
  * `SnapshotLoaded` or `SnapshotSaved` reply carrying an `Either` that distinguishes success from failure.
  */
object PersistenceProtocol {

  /** Super-type for all messages sent to a persistence actor. */
  trait Command

  // --- Commands (sent to PostgresActor) ---

  /** Fetch the latest stored snapshot for the given game; replies with [[SnapshotLoaded]]. */
  final case class LoadSnapshot(gameId: GameId, gameType: GameType, replyTo: ActorRef[SnapshotLoaded]) extends Command

  /** Persist (insert or update) the full game snapshot; replies with [[SnapshotSaved]]. */
  final case class SaveSnapshot(
      gameId: GameId,
      gameType: GameType,
      game: Game[_, _, _, _, _],
      replyTo: ActorRef[SnapshotSaved]
  ) extends Command

  // --- Replies (sent back to the requester) ---

  /** Reply to [[LoadSnapshot]]; `Right(Some(game))` on hit, `Right(None)` on miss, `Left` on error. */
  final case class SnapshotLoaded(result: Either[Throwable, Option[Game[_, _, _, _, _]]])

  /** Reply to [[SaveSnapshot]]; `Right(())` on success, `Left` on error. */
  final case class SnapshotSaved(result: Either[Throwable, Unit])
}
