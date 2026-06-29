package com.andy327.persistence.db.postgres

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie.Transactor
import doobie.implicits._
import doobie.postgres.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameType, MatchId, PlayerId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

class PostgresPlayerHistoryRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  /** Single PostgreSQL container spun up once per ScalaTest run. */
  override val container: PostgreSQLContainer = PostgreSQLContainer()

  private lazy val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = container.jdbcUrl,
    user = container.username,
    password = container.password,
    None
  )

  private lazy val repo = new PostgresPlayerHistoryRepository(xa)

  override def afterStart(): Unit =
    repo.initialize().unsafeRunSync()

  "PostgresPlayerHistoryRepository" should {
    "record a completed game and return it with its fields intact" in {
      val player: PlayerId = UUID.randomUUID()
      val matchId: MatchId = UUID.randomUUID()

      repo.record(player, matchId, GameType.ConnectFour, GameResult.Loss, forfeit = true).unsafeRunSync()

      val records = repo.findByPlayer(player).unsafeRunSync()
      records should have size 1
      val record = records.head
      record.matchId shouldBe matchId
      record.gameType shouldBe GameType.ConnectFour
      record.result shouldBe GameResult.Loss
      record.forfeit shouldBe true
      record.finishedAt should not be null
    }

    "record both participants of the same game under their own ids" in {
      val winner: PlayerId = UUID.randomUUID()
      val loser: PlayerId = UUID.randomUUID()
      val matchId: MatchId = UUID.randomUUID()

      repo.record(winner, matchId, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.record(loser, matchId, GameType.TicTacToe, GameResult.Loss, forfeit = false).unsafeRunSync()

      repo.findByPlayer(winner).unsafeRunSync().map(_.result) shouldBe List(GameResult.Win)
      repo.findByPlayer(loser).unsafeRunSync().map(_.result) shouldBe List(GameResult.Loss)
    }

    "order a player's games by most recently finished first" in {
      val player: PlayerId = UUID.randomUUID()
      val older: MatchId = UUID.randomUUID()
      val newer: MatchId = UUID.randomUUID()

      // insert directly with controlled finished_at so the ordering assertion is deterministic
      insertAt(player, older, Instant.parse("2026-01-01T00:00:00Z"))
      insertAt(player, newer, Instant.parse("2026-06-01T00:00:00Z"))

      repo.findByPlayer(player).unsafeRunSync().map(_.matchId) shouldBe List(newer, older)
    }

    "ignore a re-recorded (playerId, matchId) outcome rather than failing or overwriting" in {
      val player: PlayerId = UUID.randomUUID()
      val matchId: MatchId = UUID.randomUUID()

      repo.record(player, matchId, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.record(player, matchId, GameType.TicTacToe, GameResult.Loss, forfeit = false).unsafeRunSync()

      repo.findByPlayer(player).unsafeRunSync().map(_.result) shouldBe List(GameResult.Win) // first write wins
    }

    "skip rows whose stored game type or result cannot be parsed" in {
      val player: PlayerId = UUID.randomUUID()
      val good: MatchId = UUID.randomUUID()
      val badType: MatchId = UUID.randomUUID()
      val badResult: MatchId = UUID.randomUUID()

      repo.record(player, good, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      // corrupt rows inserted directly, bypassing record (which always writes valid labels)
      sql"""
        INSERT INTO player_games (player_id, game_id, game_type, result, forfeit)
        VALUES (${player.toString}, ${badType.toString}, 'Hopscotch', 'win', false)
      """.update.run.transact(xa).unsafeRunSync()
      sql"""
        INSERT INTO player_games (player_id, game_id, game_type, result, forfeit)
        VALUES (${player.toString}, ${badResult.toString}, 'TicTacToe', 'maybe', false)
      """.update.run.transact(xa).unsafeRunSync()

      repo.findByPlayer(player).unsafeRunSync().map(_.matchId) shouldBe List(good) // only the parseable row survives
    }

    "isolate history by playerId" in {
      val p1: PlayerId = UUID.randomUUID()
      val p2: PlayerId = UUID.randomUUID()

      repo.record(p1, UUID.randomUUID(), GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.record(p2, UUID.randomUUID(), GameType.TicTacToe, GameResult.Draw, forfeit = false).unsafeRunSync()

      repo.findByPlayer(p1).unsafeRunSync().map(_.result) shouldBe List(GameResult.Win)
      repo.findByPlayer(p2).unsafeRunSync().map(_.result) shouldBe List(GameResult.Draw)
    }

    "return no history for an unknown player" in {
      repo.findByPlayer(UUID.randomUUID()).unsafeRunSync() shouldBe empty
    }
  }

  /** Inserts a row with an explicit `finished_at`, bypassing `record`'s `now()` default, for deterministic ordering. */
  private def insertAt(playerId: PlayerId, matchId: MatchId, finishedAt: Instant): Unit =
    sql"""
      INSERT INTO player_games (player_id, game_id, game_type, result, forfeit, finished_at)
      VALUES (${playerId.toString}, ${matchId.toString}, 'TicTacToe', 'win', false, $finishedAt)
    """.update.run.transact(xa).unsafeRunSync()
}
