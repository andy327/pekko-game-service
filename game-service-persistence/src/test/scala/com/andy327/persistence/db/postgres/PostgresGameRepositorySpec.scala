package com.andy327.persistence.db.postgres

import java.util.UUID

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie.Transactor
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameId, GameType, PlayerId}
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
    gameRepo.initialize().unsafeRunSync()
  }

  "PostgresGameRepository" should {
    "save then load a TicTacToe game" in {
      val gameId: GameId = UUID.randomUUID()
      val alice: PlayerId = UUID.randomUUID()
      val bob: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(alice, bob)

      gameRepo.saveGame(gameId, GameType.TicTacToe, game).unsafeRunSync()
      gameRepo.loadGame(gameId, GameType.TicTacToe).unsafeRunSync() shouldBe Some(game)
    }

    "return None for a missing game" in {
      gameRepo.loadGame(UUID.randomUUID(), GameType.TicTacToe).unsafeRunSync() shouldBe None
    }

    "return None for a game with invalid JSON" in {
      val invalidJson = "invalid-json"
      val gameId: GameId = UUID.randomUUID()
      val insert = sql"""
        INSERT INTO games (game_id, game_type, game_state)
        VALUES (${gameId.toString}, 'TicTacToe', $invalidJson)
      """.update.run.transact(xa)

      insert.unsafeRunSync()

      gameRepo.loadGame(gameId, GameType.TicTacToe).unsafeRunSync() shouldBe None
    }

    "list all saved games" in {
      val alice: PlayerId = UUID.randomUUID()
      val bob: PlayerId = UUID.randomUUID()
      val gameId1 = UUID.randomUUID()
      val gameId2 = UUID.randomUUID()
      gameRepo.saveGame(gameId1, GameType.TicTacToe, TicTacToe.empty(alice, bob)).unsafeRunSync()
      gameRepo.saveGame(gameId2, GameType.TicTacToe, TicTacToe.empty(alice, bob)).unsafeRunSync()

      val all = gameRepo.loadAllGames().unsafeRunSync()
      (all.keySet should contain).allOf(gameId1, gameId2)
    }

    "skip corrupted or unknown game types when loading all games" in {
      val badTypeGameId: GameId = UUID.randomUUID()
      val invalidGameId: String = "abc"
      val corruptedGameId: GameId = UUID.randomUUID()
      val validGameId: GameId = UUID.randomUUID()
      val x: PlayerId = UUID.randomUUID()
      val y: PlayerId = UUID.randomUUID()
      val validGame = TicTacToe.empty(x, y)

      // Insert valid, corrupted, and unknown-type rows
      val insertAll = List(
        sql"""INSERT INTO games (game_id, game_type, game_state)
              VALUES (${badTypeGameId.toString}, 'UnknownGame', '{}')""",
        sql"""INSERT INTO games (game_id, game_type, game_state)
              VALUES (${invalidGameId}, 'TicTacToe', ${validGame.asJson.noSpaces})""",
        sql"""INSERT INTO games (game_id, game_type, game_state)
              VALUES (${corruptedGameId.toString}, 'TicTacToe', 'corrupted-json')""",
        sql"""INSERT INTO games (game_id, game_type, game_state)
              VALUES (${validGameId.toString}, 'TicTacToe', ${validGame.asJson.noSpaces})"""
      ).traverse_(_.update.run).transact(xa)

      insertAll.unsafeRunSync()

      val result = gameRepo.loadAllGames().unsafeRunSync()
      result.keySet should contain(validGameId)
      result.keySet should not contain badTypeGameId
      result.keySet should not contain invalidGameId
      result.keySet should not contain corruptedGameId
    }
  }
}
