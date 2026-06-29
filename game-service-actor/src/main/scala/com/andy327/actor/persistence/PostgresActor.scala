package com.andy327.actor.persistence

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import org.apache.pekko.actor.typed.Behavior

import com.andy327.model.core.{Game, GameType, MatchId, PlayerId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult
import com.andy327.persistence.db.{
  GameRepository,
  InMemoryPlayerHistoryRepository,
  MoveHistoryRepository,
  PlayerHistoryRepository
}

/** Concrete [[PersistActor]] that stores game snapshots, move-history records, and per-player game results in
  * PostgreSQL.
  *
  * Spawned once at startup by `GameServer` and shared across all game actors.
  */
object PostgresActor {

  /** @param gameRepo the repository used to read and write game snapshots
    * @param moveRepo the repository used to append move-history records
    * @param playerHistoryRepo the repository used to record per-player game results
    * @param runtime the Cats Effect runtime used to execute IO operations
    */
  def apply(
      gameRepo: GameRepository,
      moveRepo: MoveHistoryRepository,
      playerHistoryRepo: PlayerHistoryRepository = new InMemoryPlayerHistoryRepository
  )(implicit runtime: IORuntime): Behavior[PersistenceProtocol.Command] =
    new Impl(gameRepo, moveRepo, playerHistoryRepo).behavior

  private class Impl(
      gameRepo: GameRepository,
      moveRepo: MoveHistoryRepository,
      playerHistoryRepo: PlayerHistoryRepository
  ) extends PersistActor {
    def saveToStore(matchId: MatchId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] =
      gameRepo.saveGame(matchId, gameType, game)

    def appendMoveToStore(matchId: MatchId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] =
      moveRepo.appendMove(matchId, seq, playerId, move)

    def recordGameResultToStore(
        playerId: PlayerId,
        matchId: MatchId,
        gameType: GameType,
        result: GameResult,
        forfeit: Boolean
    ): IO[Unit] =
      playerHistoryRepo.record(playerId, matchId, gameType, result, forfeit)
  }
}
