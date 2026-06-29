package com.andy327.persistence.db.postgres

import java.time.Instant
import java.util.UUID

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.implicits._

import doobie._
import doobie.implicits._
import doobie.postgres.implicits._
import io.circe.Json
import io.circe.parser.parse

import com.andy327.model.core.{MatchId, PlayerId}
import com.andy327.persistence.db.{MoveHistoryRepository, MoveRecord}

/** [[MoveHistoryRepository]] backed by PostgreSQL via Doobie.
  *
  * Each move is one row in `game_moves`, keyed by `(game_id, seq)`. The move payload is stored as an opaque JSON
  * string; `created_at` is stamped by the database on insert.
  */
class PostgresMoveHistoryRepository(xa: Transactor[IO]) extends MoveHistoryRepository {

  private val logger = LoggerFactory.getLogger(getClass)

  /** Creates the 'game_moves' table with the appropriate schema if it doesn't already exist. */
  override def initialize(): IO[Unit] =
    sql"""
      CREATE TABLE IF NOT EXISTS game_moves (
        game_id    TEXT NOT NULL,
        seq        INTEGER NOT NULL,
        player_id  TEXT NOT NULL,
        move_json  TEXT NOT NULL,
        created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
        PRIMARY KEY (game_id, seq)
      )
    """.update.run.transact(xa).void

  /** Appends a move row. The `(game_id, seq)` primary key makes a duplicate ordinal a no-op rather than a duplicate. */
  override def appendMove(matchId: MatchId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] =
    sql"""
      INSERT INTO game_moves (game_id, seq, player_id, move_json)
      VALUES (${matchId.toString}, $seq, ${playerId.toString}, ${move.noSpaces})
      ON CONFLICT (game_id, seq) DO NOTHING
    """.update.run.transact(xa).void

  /** Loads all moves for a game ordered by ascending seq. Rows that fail to parse are logged and skipped. */
  override def loadMoves(matchId: MatchId): IO[List[MoveRecord]] =
    sql"""
      SELECT seq, player_id, move_json, created_at
      FROM game_moves
      WHERE game_id = ${matchId.toString}
      ORDER BY seq ASC
    """
      .query[(Int, String, String, Instant)]
      .to[List]
      .transact(xa)
      .map { rows =>
        rows.flatMap { case (seq, playerIdStr, moveJson, createdAt) =>
          val record = for {
            playerId <- Either.catchOnly[IllegalArgumentException](UUID.fromString(playerIdStr))
            move <- parse(moveJson)
          } yield MoveRecord(seq, playerId, move, createdAt)

          record match {
            case Right(rec) => List(rec)
            case Left(err)  =>
              logger.warn(s"Skipping unparseable move $seq for game ${matchId.toString}: ${err.getMessage}")
              Nil
          }
        }
      }
}
