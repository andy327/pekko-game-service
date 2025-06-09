package com.andy327.persistence.db.postgres

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie._
import doobie.implicits._
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers

import com.andy327.model.core.GameType
import com.andy327.model.tictactoe.TicTacToe

class PostgresGameRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  /** Single PostgreSQL container spun up once per ScalaTest run. */
  override val container: PostgreSQLContainer = PostgreSQLContainer()

  /** Doobie transactor wired to the running container */
  private lazy val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver   = "org.postgresql.Driver",
    url      = container.jdbcUrl,
    user     = container.username,
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

    "list all saved games" in {
      gameRepo.saveGame("g2", GameType.TicTacToe, TicTacToe.empty("carl", "david")).unsafeRunSync()

      val all = gameRepo.loadAllGames().unsafeRunSync()
      all.keySet should contain theSameElementsAs Set("g1", "g2")
    }
  }
}
