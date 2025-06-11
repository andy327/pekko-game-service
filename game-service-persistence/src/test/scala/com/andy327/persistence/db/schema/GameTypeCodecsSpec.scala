package com.andy327.persistence.db.schema

import io.circe.parser._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameType
import com.andy327.model.tictactoe.TicTacToe

class GameTypeCodecsSpec extends AnyWordSpec with Matchers {
  import GameTypeCodecs._

  "GameTypeCodecs" should {
    "correctly encode and decode GameType.TicTacToe" in {
      val t: GameType = GameType.TicTacToe
      val json = t.asJson.noSpaces
      json shouldBe "\"TicTacToe\""

      val decoded = decode[GameType](json)
      decoded shouldBe Right(GameType.TicTacToe)
    }

    "fail to decode an unknown GameType string" in {
      val json = "\"UnknownGame\""
      val result = decode[GameType](json)
      result.isLeft shouldBe true
      result.swap.getOrElse(fail("Expected a Left but got a Right")).getMessage should include("Unknown GameType")
    }

    "deserialize a valid TicTacToe game from JSON" in {
      val game = TicTacToe.empty("alice", "bob")
      val json = game.asJson.noSpaces

      val result = deserializeGame(GameType.TicTacToe, json)
      result shouldBe Right(game)
    }

    "return Left if game JSON is invalid" in {
      val badJson = """{ "not": "valid" }"""

      val result = deserializeGame(GameType.TicTacToe, badJson)
      result.isLeft shouldBe true
    }
  }
}
