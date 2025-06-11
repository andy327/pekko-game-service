package com.andy327.persistence.db.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie.Transactor
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameType
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.schema.GameTypeCodecs

class PostgresGameRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {
  import GameTypeCodecs._
  import io.circe.syntax._

  /** Single PostgreSQL container spun up once per ScalaTest run. */
  override val container: PostgreSQLContainer = PostgreSQLContainer()

  /** Doobie transactor wired to the running container */
  private lazy val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = container.jdbcUrl,
    user = container.username,
    password = container.password,
    None
  )

  private lazy val gameRepo = new PostgresGameRepository(xa)

  /** Create the minimal schema after the container is ready. */
  override def afterStart(): Unit = {
    val createTable = sql"""
      CREATE TABLE IF NOT EXISTS games (
        game_id    TEXT PRIMARY KEY,
        game_type  TEXT NOT NULL,
        game_state TEXT NOT NULL
      )
    """.update.run.transact(xa)

    createTable.unsafeRunSync()
  }

  "PostgresGameRepository" should {
    "save then load a TicTacToe game" in {
      val gameId = "g1"
      val game = TicTacToe.empty("alice", "bob")

      gameRepo.saveGame(gameId, GameType.TicTacToe, game).unsafeRunSync()
      gameRepo.loadGame(gameId, GameType.TicTacToe).unsafeRunSync() shouldBe Some(game)
    }

    "return None for a missing game" in {
      gameRepo.loadGame("missing", GameType.TicTacToe).unsafeRunSync() shouldBe None
    }

    "return None for a game with invalid JSON" in {
      val invalidJson = "not-a-json"
      val gameId = "bad-json"
      val insert = sql"""
        INSERT INTO games (game_id, game_type, game_state)
        VALUES ($gameId, 'TicTacToe', $invalidJson)
      """.update.run.transact(xa)

      insert.unsafeRunSync()

      gameRepo.loadGame(gameId, GameType.TicTacToe).unsafeRunSync() shouldBe None
    }

    "list all saved games" in {
      gameRepo.saveGame("g2", GameType.TicTacToe, TicTacToe.empty("carl", "david")).unsafeRunSync()

      val all = gameRepo.loadAllGames().unsafeRunSync()
      all.keySet should contain theSameElementsAs Set("g1", "g2")
    }

    "skip corrupted or unknown game types when loading all games" in {
      val badTypeGameId = "bad-type"
      val corruptedGameId = "bad-json-2"
      val validGameId = "g3"
      val validGame = TicTacToe.empty("x", "y")

      // Insert valid, corrupted, and unknown-type rows
      val insertAll = List(
        sql"""INSERT INTO games (game_id, game_type, game_state) VALUES ($badTypeGameId, 'UnknownGame', '{}')""",
        sql"""INSERT INTO games (game_id, game_type, game_state) VALUES ($corruptedGameId, 'TicTacToe', 'corrupted-json')""",
        sql"""INSERT INTO games (game_id, game_type, game_state) VALUES ($validGameId, 'TicTacToe', ${validGame.asJson.noSpaces})"""
      ).traverse_(_.update.run).transact(xa)

      insertAll.unsafeRunSync()

      val result = gameRepo.loadAllGames().unsafeRunSync()
      result.keySet should contain(validGameId)
      result.keySet should not contain badTypeGameId
      result.keySet should not contain corruptedGameId
    }
  }
}
