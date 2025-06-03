package com.andy327.server.db.postgres

import com.andy327.model.tictactoe.TicTacToe
import com.andy327.server.db.GameRepository

import cats.effect._
import cats.implicits._
import doobie._
import doobie.implicits._
import io.circe.generic.auto._
import io.circe.syntax._
import io.circe.parser.decode

import org.slf4j.LoggerFactory

/**
 * GameRepository implementation that uses PostgreSQL via Doobie to persist and retrieve game states.
 * Game state is serialized to JSON using Circe and stored as a string in the database.
 */
class PostgresGameRepository(xa: Transactor[IO]) extends GameRepository {
  private val logger = LoggerFactory.getLogger(getClass)

  /**
   * Saves the current state of a game into the database.
   * If the gameId already exists, the existing row is updated.
   */
  override def saveGame(gameId: String, game: TicTacToe): IO[Unit] = {
    val jsonStr = game.asJson.noSpaces
    sql"""INSERT INTO games (game_id, game_state) VALUES ($gameId, $jsonStr)
          ON CONFLICT (game_id) DO UPDATE SET game_state = EXCLUDED.game_state"""
      .update.run.transact(xa).void
  }

  /**
   * Loads the game state for a given gameId.
   * If the game exists, it is deserialized from JSON into a TicTacToe object.
   */
  override def loadGame(gameId: String): IO[Option[TicTacToe]] = {
    sql"SELECT game_state FROM games WHERE game_id = $gameId"
      .query[String]
      .option
      .transact(xa)
      .flatMap {
        case Some(jsonStr) =>
          IO.fromEither(decode[TicTacToe](jsonStr).left.map(err => new Exception(err)))
            .map(Some(_))
            .handleErrorWith { err =>
              logger.warn(s"Failed to decode game $gameId: ${err.getMessage}")
              IO.pure(None)
            }
        case None => IO.pure(None)
      }
  }

  /**
   * Loads all games stored in the database and returns them as a Map from gameId to game state.
   * If any games fail to decode from JSON, their errors are logged and we proceed with loading.
   */
  override def loadAllGames(): IO[Map[String, TicTacToe]] = {
    sql"SELECT game_id, game_state FROM games"
      .query[(String, String)]
      .to[List]
      .transact(xa)
      .flatMap { rows =>
        rows.flatTraverse {
          case (id, jsonStr) =>
            decode[TicTacToe](jsonStr) match {
              case Right(game) => IO.pure(List(id -> game))
              case Left(err) =>
                IO {
                  logger.warn(s"Skipping corrupted game $id: ${err.getMessage}")
                  Nil
                }
            }
        }.map(_.toMap)
      }
  }
}
