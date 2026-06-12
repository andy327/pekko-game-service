package com.andy327.server.actors.persistence

import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.{Game, GameId, GameType}

/** Commands and reply types for the persistence actor (e.g. [[PostgresActor]]).
  *
  * Senders fire a `SaveSnapshot` command with a typed `replyTo` ref and receive a `SnapshotSaved` reply carrying an
  * `Either` that distinguishes success from failure. Loads do not go through the actor: startup restore and
  * completed-game reads call the repository directly.
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

  // --- Replies (sent back to the requester) ---

  /** Reply to [[SaveSnapshot]]; `Right(())` on success, `Left` on error. */
  final case class SnapshotSaved(result: Either[Throwable, Unit])
}
