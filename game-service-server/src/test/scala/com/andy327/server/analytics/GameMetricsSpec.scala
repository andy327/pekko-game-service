package com.andy327.server.analytics

import java.util.UUID

import io.prometheus.client.CollectorRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.events.GameEvent._
import com.andy327.model.core.GameType

class GameMetricsSpec extends AnyWordSpec with Matchers {

  private def fixture: (CollectorRegistry, GameMetrics) = {
    val registry = new CollectorRegistry()
    (registry, new GameMetrics(registry))
  }

  private def sample(registry: CollectorRegistry, name: String, labelNames: Array[String], labelValues: Array[String]) =
    Option(registry.getSampleValue(name, labelNames, labelValues)).map(_.doubleValue)

  "GameMetrics" should {
    "count games started by game type" in {
      val (registry, metrics) = fixture
      metrics.record(GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2))
      metrics.record(GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2))
      metrics.record(GameStarted(UUID.randomUUID(), GameType.ConnectFour, 2))

      sample(registry, "games_started_total", Array("game_type"), Array("tictactoe")) shouldBe Some(2.0)
      sample(registry, "games_started_total", Array("game_type"), Array("connectfour")) shouldBe Some(1.0)
    }

    "count moves and record the per-game move distribution on completion" in {
      val (registry, metrics) = fixture
      val matchId = UUID.randomUUID()
      metrics.record(MoveMade(matchId, GameType.TicTacToe, UUID.randomUUID(), 0))
      metrics.record(MoveMade(matchId, GameType.TicTacToe, UUID.randomUUID(), 1))
      metrics.record(GameCompleted(matchId, GameType.TicTacToe, Outcome.Won, 9))

      sample(registry, "moves_total", Array("game_type"), Array("tictactoe")) shouldBe Some(2.0)
      sample(registry, "game_moves_count", Array("game_type"), Array("tictactoe")) shouldBe Some(1.0)
      sample(registry, "game_moves_sum", Array("game_type"), Array("tictactoe")) shouldBe Some(9.0)
    }

    "count completions split by outcome" in {
      val (registry, metrics) = fixture
      metrics.record(GameCompleted(UUID.randomUUID(), GameType.TicTacToe, Outcome.Won, 5))
      metrics.record(GameCompleted(UUID.randomUUID(), GameType.TicTacToe, Outcome.Draw, 9))
      metrics.record(GameCompleted(UUID.randomUUID(), GameType.TicTacToe, Outcome.Forfeit, 1))

      sample(registry, "games_completed_total", Array("game_type", "outcome"), Array("tictactoe", "won")) shouldBe Some(
        1.0
      )
      sample(
        registry,
        "games_completed_total",
        Array("game_type", "outcome"),
        Array("tictactoe", "draw")
      ) shouldBe Some(1.0)
      sample(
        registry,
        "games_completed_total",
        Array("game_type", "outcome"),
        Array("tictactoe", "forfeit")
      ) shouldBe Some(1.0)
    }

    "label Pig events with the pig game type" in {
      val (registry, metrics) = fixture
      metrics.record(GameStarted(UUID.randomUUID(), GameType.Pig, 3))

      sample(registry, "games_started_total", Array("game_type"), Array("pig")) shouldBe Some(1.0)
    }

    "label Mastermind events with the mastermind game type" in {
      val (registry, metrics) = fixture
      metrics.record(GameStarted(UUID.randomUUID(), GameType.Mastermind, 2))

      sample(registry, "games_started_total", Array("game_type"), Array("mastermind")) shouldBe Some(1.0)
    }

    "label Liar's Dice events with the liarsdice game type" in {
      val (registry, metrics) = fixture
      metrics.record(GameStarted(UUID.randomUUID(), GameType.LiarsDice, 4))

      sample(registry, "games_started_total", Array("game_type"), Array("liarsdice")) shouldBe Some(1.0)
    }

    "label Texas Hold 'Em events with the texasholdem game type" in {
      val (registry, metrics) = fixture
      metrics.record(GameStarted(UUID.randomUUID(), GameType.TexasHoldEm, 6))

      sample(registry, "games_started_total", Array("game_type"), Array("texasholdem")) shouldBe Some(1.0)
    }

    "label lobby chat as lobby and in-game chat by game type" in {
      val (registry, metrics) = fixture
      metrics.record(ChatSent(UUID.randomUUID(), Some(GameType.Battleship)))
      metrics.record(ChatSent(UUID.randomUUID(), None))

      sample(registry, "chat_messages_total", Array("game_type"), Array("battleship")) shouldBe Some(1.0)
      sample(registry, "chat_messages_total", Array("game_type"), Array("lobby")) shouldBe Some(1.0)
    }
  }
}
