package com.andy327.persistence.db.postgres

import java.time.Instant
import java.util.UUID

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.implicits._

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

import com.andy327.model.core.{GameId, GameType, PlayerId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult
import com.andy327.persistence.db.{PlayerGameRecord, PlayerHistoryRepository}

/** [[PlayerHistoryRepository]] backed by PostgreSQL via Doobie.
  *
  * Each participant's outcome is one row in `player_games`, keyed by `(player_id, game_id)`; `finished_at` is stamped
  * by the database on insert. Ids and the game type are stored as text and parsed back on read, mirroring the other
  * history table ([[PostgresMoveHistoryRepository]]).
  */
class PostgresPlayerHistoryRepository(xa: Transactor[IO]) extends PlayerHistoryRepository {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Creates the 'player_games' table with the appropriate schema if it doesn't already exist. The `player_id`-led
    * primary key also serves the by-player lookup, so no separate index is needed.
    */
  override def initialize(): IO[Unit] =
    sql"""
      CREATE TABLE IF NOT EXISTS player_games (
        player_id   TEXT NOT NULL,
        game_id     TEXT NOT NULL,
        game_type   TEXT NOT NULL,
        result      TEXT NOT NULL,
        forfeit     BOOLEAN NOT NULL DEFAULT false,
        finished_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        PRIMARY KEY (player_id, game_id)
      )
    """.update.run.transact(xa).void

  /** Records one participant's outcome. The `(player_id, game_id)` primary key makes a re-delivered completion a no-op
    * rather than a duplicate row.
    */
  override def record(
      playerId: PlayerId,
      gameId: GameId,
      gameType: GameType,
      result: GameResult,
      forfeit: Boolean
  ): IO[Unit] =
    sql"""
      INSERT INTO player_games (player_id, game_id, game_type, result, forfeit)
      VALUES (${playerId.toString}, ${gameId.toString}, ${gameType.toString}, ${result.label}, $forfeit)
      ON CONFLICT (player_id, game_id) DO NOTHING
    """.update.run.transact(xa).void

  /** Loads all of a player's completed games ordered by most recently finished first. Rows whose game id, type, or
    * result fail to parse are logged and skipped.
    */
  override def findByPlayer(playerId: PlayerId): IO[List[PlayerGameRecord]] =
    sql"""
      SELECT game_id, game_type, result, forfeit, finished_at
      FROM player_games
      WHERE player_id = ${playerId.toString}
      ORDER BY finished_at DESC
    """
      .query[(String, String, String, Boolean, Instant)]
      .to[List]
      .transact(xa)
      .map { rows =>
        rows.flatMap { case (gameIdStr, gameTypeStr, resultStr, forfeit, finishedAt) =>
          val record = for {
            gameId <- Either.catchOnly[IllegalArgumentException](UUID.fromString(gameIdStr))
            gameType <- GameType.fromString(gameTypeStr).toRight(new Exception(s"Unknown GameType: $gameTypeStr"))
            result <- GameResult.fromLabel(resultStr).toRight(new Exception(s"Unknown GameResult: $resultStr"))
          } yield PlayerGameRecord(gameId, gameType, result, forfeit, finishedAt)

          record match {
            case Right(rec) => List(rec)
            case Left(err)  =>
              logger.warn(s"Skipping unparseable history row for player ${playerId.toString}: ${err.getMessage}")
              Nil
          }
        }
      }
}
