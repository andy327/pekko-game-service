package com.andy327.server.analytics

import java.util.UUID

import io.circe.parser.decode
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.events.GameEvent
import com.andy327.actor.events.GameEvent._
import com.andy327.model.core.GameType
import com.andy327.server.analytics.GameEventCodecs._

class GameEventSpec extends AnyWordSpec with Matchers {

  private def roundTrip(event: GameEvent): Unit =
    decode[GameEvent](event.asJson.noSpaces) shouldBe Right(event)

  "GameEvent codec" should {
    "round-trip GameStarted" in roundTrip(GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2))

    "round-trip MoveMade" in roundTrip(MoveMade(UUID.randomUUID(), GameType.ConnectFour, UUID.randomUUID(), 4))

    "round-trip each GameCompleted outcome" in
      Outcome.all.foreach(o => roundTrip(GameCompleted(UUID.randomUUID(), GameType.Battleship, o, 17)))

    "round-trip ChatSent in a game and in a lobby" in {
      roundTrip(ChatSent(UUID.randomUUID(), Some(GameType.TicTacToe)))
      roundTrip(ChatSent(UUID.randomUUID(), None))
    }

    "emit a type discriminator" in {
      val event: GameEvent = GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2)
      event.asJson.noSpaces should include(""""type":"GameStarted"""")
    }

    "reject an unknown type" in {
      decode[GameEvent]("""{"type":"Nope"}""").isLeft shouldBe true
    }
  }
}
