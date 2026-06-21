package com.andy327.server.analytics

import java.util.UUID

import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameType
import com.andy327.server.analytics.GameAnalyticsEvent._

class GameAnalyticsEventSpec extends AnyWordSpec with Matchers {

  private def roundTrip(event: GameAnalyticsEvent): Unit =
    decode[GameAnalyticsEvent](event.asJson.noSpaces) shouldBe Right(event)

  "GameAnalyticsEvent codec" should {
    "round-trip GameStarted" in roundTrip(GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2))

    "round-trip MoveMade" in roundTrip(MoveMade(UUID.randomUUID(), GameType.ConnectFour, UUID.randomUUID(), 4))

    "round-trip each GameCompleted outcome" in
      Outcome.all.foreach(o => roundTrip(GameCompleted(UUID.randomUUID(), GameType.Battleship, o, 17)))

    "round-trip ChatSent in a game and in a lobby" in {
      roundTrip(ChatSent(UUID.randomUUID(), Some(GameType.TicTacToe)))
      roundTrip(ChatSent(UUID.randomUUID(), None))
    }

    "emit a type discriminator" in {
      val event: GameAnalyticsEvent = GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2)
      event.asJson.noSpaces should include(""""type":"GameStarted"""")
    }

    "reject an unknown type" in {
      decode[GameAnalyticsEvent]("""{"type":"Nope"}""").isLeft shouldBe true
    }
  }
}
