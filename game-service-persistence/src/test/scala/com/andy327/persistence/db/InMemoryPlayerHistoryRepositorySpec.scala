package com.andy327.persistence.db

import java.util.UUID

import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameType
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

class InMemoryPlayerHistoryRepositorySpec extends AnyWordSpec with Matchers {

  "InMemoryPlayerHistoryRepository" should {
    "treat initialize as a no-op, leaving the repository usable" in {
      val repo = new InMemoryPlayerHistoryRepository
      repo.initialize().unsafeRunSync() // no schema to create; must complete without effect

      val player = UUID.randomUUID()
      val game = UUID.randomUUID()
      repo.record(player, game, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.findByPlayer(player).unsafeRunSync().map(_.gameId) shouldBe List(game)
    }

    "record a completed game and return it with its fields intact" in {
      val repo = new InMemoryPlayerHistoryRepository
      val player = UUID.randomUUID()
      val game = UUID.randomUUID()

      repo.record(player, game, GameType.ConnectFour, GameResult.Loss, forfeit = true).unsafeRunSync()

      val records = repo.findByPlayer(player).unsafeRunSync()
      records should have size 1
      val record = records.head
      record.gameId shouldBe game
      record.gameType shouldBe GameType.ConnectFour
      record.result shouldBe GameResult.Loss
      record.forfeit shouldBe true
      record.finishedAt should not be null
    }

    "record both participants of the same game under their own ids" in {
      val repo = new InMemoryPlayerHistoryRepository
      val winner = UUID.randomUUID()
      val loser = UUID.randomUUID()
      val game = UUID.randomUUID()

      repo.record(winner, game, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.record(loser, game, GameType.TicTacToe, GameResult.Loss, forfeit = false).unsafeRunSync()

      repo.findByPlayer(winner).unsafeRunSync().map(_.result) shouldBe List(GameResult.Win)
      repo.findByPlayer(loser).unsafeRunSync().map(_.result) shouldBe List(GameResult.Loss)
    }

    "return a player's games most recently finished first" in {
      val repo = new InMemoryPlayerHistoryRepository
      val player = UUID.randomUUID()
      val older = UUID.randomUUID()
      val newer = UUID.randomUUID()

      repo.record(player, older, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.record(player, newer, GameType.TicTacToe, GameResult.Draw, forfeit = false).unsafeRunSync()

      repo.findByPlayer(player).unsafeRunSync().map(_.gameId) shouldBe List(newer, older)
    }

    "ignore a re-recorded (player, game) outcome rather than duplicating it" in {
      val repo = new InMemoryPlayerHistoryRepository
      val player = UUID.randomUUID()
      val game = UUID.randomUUID()

      repo.record(player, game, GameType.TicTacToe, GameResult.Win, forfeit = false).unsafeRunSync()
      repo.record(player, game, GameType.TicTacToe, GameResult.Loss, forfeit = false).unsafeRunSync()

      val records = repo.findByPlayer(player).unsafeRunSync()
      records.map(_.result) shouldBe List(GameResult.Win) // first write wins, no duplicate
    }

    "return no history for an unknown player" in {
      val repo = new InMemoryPlayerHistoryRepository
      repo.findByPlayer(UUID.randomUUID()).unsafeRunSync() shouldBe empty
    }
  }
}
