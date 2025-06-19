package com.andy327.persistence.db.postgres

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.implicits._

import doobie._
import doobie.implicits._

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository
import com.andy327.persistence.db.schema.GameTypeCodecs

/**
 * GameRepository implementation that uses PostgreSQL via Doobie to persist and retrieve game states.
 * Game state is serialized to JSON using Circe and stored as a string in the database.
 */
class PostgresGameRepository(xa: Transactor[IO]) extends GameRepository {
  import GameTypeCodecs._
  import io.circe.syntax._

  private val logger = LoggerFactory.getLogger(getClass)

  private def parseGameType(str: String): Either[Throwable, GameType] =
    str match {
      case "TicTacToe" => Right(GameType.TicTacToe)
      case other       => Left(new Exception(s"Unknown GameType: $other"))
    }

  /**
   * Creates the 'games' table with the appropriate schema if it doesn't already exit.
   */
  override def initialize(): IO[Unit] =
    sql"""
      CREATE TABLE IF NOT EXISTS games (
        game_id    TEXT PRIMARY KEY,
        game_type  TEXT NOT NULL,
        game_state TEXT NOT NULL
      )
    """.update.run.transact(xa).void

  /**
   * Saves the current state of a game of the given gameType into the database.
   * If the gameId already exists, the existing row is updated.
   */
  override def saveGame(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = {
    val (jsonStr, gameTypeStr) = gameType match {
      case GameType.TicTacToe =>
        val typedGame = game.asInstanceOf[TicTacToe]
        (typedGame.asJson.noSpaces, "TicTacToe")
    }

    sql"""
      INSERT INTO games (game_id, game_type, game_state)
      VALUES ($gameId, $gameTypeStr, $jsonStr)
      ON CONFLICT (game_id) DO UPDATE
      SET game_type = EXCLUDED.game_type,
          game_state = EXCLUDED.game_state
    """.update.run.transact(xa).void
  }

  /**
   * Loads the game state for a given gameId and gameType.
   * If the game exists, it is deserialized from JSON into a TicTacToe object.
   */
  override def loadGame(gameId: String, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] =
    sql"SELECT game_state FROM games WHERE game_id = $gameId"
      .query[String]
      .option
      .transact(xa)
      .flatMap {
        case Some(jsonStr) =>
          IO.fromEither(GameTypeCodecs.deserializeGame(gameType, jsonStr))
            .map(Some(_))
            .handleErrorWith { err =>
              logger.warn(s"Failed to decode $gameType game $gameId: ${err.getMessage}")
              IO.pure(None)
            }
        case None => IO.pure(None)
      }

  /**
   * Loads all games stored in the database and returns them as a Map from gameId to game state.
   * If any games fail to decode from JSON, their errors are logged and we proceed with loading.
   */
  override def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] =
    sql"SELECT game_id, game_type, game_state FROM games"
      .query[(String, String, String)]
      .to[List]
      .transact(xa)
      .flatMap { rows =>
        rows.flatTraverse { case (id, gameTypeStr, jsonStr) =>
          parseGameType(gameTypeStr) match {
            case Right(gameType) =>
              GameTypeCodecs.deserializeGame(gameType, jsonStr) match {
                case Right(game) => IO.pure(List(id -> (gameType, game)))
                case Left(err)   =>
                  IO {
                    logger.warn(s"Skipping corrupted $gameType game $id: ${err.getMessage}")
                    Nil
                  }
              }
            case Left(err) =>
              IO {
                logger.warn(s"Failed to decode GameType for game $id: ${err.getMessage}")
                Nil
              }
          }
        }.map(_.toMap)
      }
}
