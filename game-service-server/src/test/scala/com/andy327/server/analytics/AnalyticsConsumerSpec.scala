package com.andy327.server.analytics

import java.util.UUID

import cats.effect.unsafe.implicits.global

import fs2.Stream
import io.circe.syntax._
import io.prometheus.client.CollectorRegistry
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.events.GameEvent
import com.andy327.actor.events.GameEvent._
import com.andy327.model.core.GameType
import com.andy327.server.analytics.GameEventCodecs._

class AnalyticsConsumerSpec extends AnyWordSpec with Matchers {

  "AnalyticsConsumer" should {
    "decode events off the stream and record them into the metrics" in {
      val registry = new CollectorRegistry()
      val metrics = new GameMetrics(registry)

      val events: List[GameEvent] = List(
        GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2),
        MoveMade(UUID.randomUUID(), GameType.TicTacToe, UUID.randomUUID(), 0),
        GameCompleted(UUID.randomUUID(), GameType.TicTacToe, Outcome.Won, 5)
      )
      val stream = Stream.emits(events.map(_.asJson.noSpaces))

      new AnalyticsConsumer(stream, metrics).run.unsafeRunSync()

      registry.getSampleValue("games_started_total", Array("game_type"), Array("tictactoe")) shouldBe 1.0
      registry.getSampleValue("moves_total", Array("game_type"), Array("tictactoe")) shouldBe 1.0
      registry.getSampleValue(
        "games_completed_total",
        Array("game_type", "outcome"),
        Array("tictactoe", "won")
      ) shouldBe 1.0
    }

    "skip malformed messages without failing the stream" in {
      val registry = new CollectorRegistry()
      val metrics = new GameMetrics(registry)

      val good: GameEvent = GameStarted(UUID.randomUUID(), GameType.ConnectFour, 2)
      val stream = Stream("not json at all", """{"type":"Unknown"}""", good.asJson.noSpaces)

      new AnalyticsConsumer(stream, metrics).run.unsafeRunSync()

      registry.getSampleValue("games_started_total", Array("game_type"), Array("connectfour")) shouldBe 1.0
    }
  }
}
