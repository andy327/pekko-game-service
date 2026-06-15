package com.andy327.server.actors.persistence

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import org.apache.pekko.actor.typed.Behavior

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}
import com.andy327.persistence.db.{GameRepository, MoveHistoryRepository}

/** Concrete [[PersistActor]] that stores game snapshots and move-history records in PostgreSQL.
  *
  * Spawned once at startup by [[com.andy327.server.GameServer]] and shared across all game actors.
  */
object PostgresActor {

  /** @param gameRepo the repository used to read and write game snapshots
    * @param moveRepo the repository used to append move-history records
    * @param runtime the Cats Effect runtime used to execute IO operations
    */
  def apply(gameRepo: GameRepository, moveRepo: MoveHistoryRepository)(implicit
      runtime: IORuntime
  ): Behavior[PersistenceProtocol.Command] =
    new Impl(gameRepo, moveRepo).behavior

  private class Impl(gameRepo: GameRepository, moveRepo: MoveHistoryRepository) extends PersistActor {
    def saveToStore(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] =
      gameRepo.saveGame(gameId, gameType, game)

    def appendMoveToStore(gameId: GameId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] =
      moveRepo.appendMove(gameId, seq, playerId, move)
  }
}
