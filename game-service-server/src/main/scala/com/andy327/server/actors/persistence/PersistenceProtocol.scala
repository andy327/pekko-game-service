package com.andy327.server.actors.persistence

import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.{Game, GameId, GameType}

/**
  * Messages understood by a persistence actor (e.g. PostgresActor).
  *
  * - `LoadSnapshot`: ask the persistence actor to fetch the latest snapshot for a given (gameId, gameType).
  * - `SaveSnapshot`: ask it to persist the entire Game object.
  * - `SnapshotLoaded` / `SnapshotSaved`: replies.
  */
object PersistenceProtocol {

  trait Command

  /** Ask the persistence actor to load a snapshot. */
  final case class LoadSnapshot(gameId: GameId, gameType: GameType, replyTo: ActorRef[SnapshotLoaded]) extends Command

  /** Ask the persistence actor to save (insert/update) a snapshot. */
  final case class SaveSnapshot(
      gameId: GameId,
      gameType: GameType,
      game: Game[_, _, _, _, _],
      replyTo: ActorRef[SnapshotSaved]
  ) extends Command

  /** Result of a LoadSnapshot request. */
  final case class SnapshotLoaded(result: Either[Throwable, Option[Game[_, _, _, _, _]]])

  /** Acknowledgement for SaveSnapshot. */
  final case class SnapshotSaved(result: Either[Throwable, Unit]) // Right(()) on success
}
