package com.andy327.persistence.db.schema

import java.util.UUID

import io.circe.parser._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameType, PlayerId}
import com.andy327.model.tictactoe.{Location, TicTacToe, X}

class GameTypeCodecsSpec extends AnyWordSpec with Matchers {
  import GameTypeCodecs._

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

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
      val game = TicTacToe.empty(alice, bob).play(X, Location(0, 0)).toOption.get
      val json = game.asJson.noSpaces
      println(json)

      val result = deserializeGame(GameType.TicTacToe, json)
      result shouldBe Right(game)
    }

    "fail to decode an unknown Mark type" in {
      val json = """{
        "playerX":"alice",
        "playerO":"bob",
        "board":[
          ["X","O","X"],
          ["O","O","X"],
          [null,"Z","O"]
        ],
        "currentPlayer":"X",
        "winner":null,
        "isDraw":false
      }"""

      val result = deserializeGame(GameType.TicTacToe, json)
      result.isLeft shouldBe true
    }

    "return Left if game JSON is invalid" in {
      val badJson = """{ "not": "valid" }"""

      val result = deserializeGame(GameType.TicTacToe, badJson)
      result.isLeft shouldBe true
    }
  }
}
