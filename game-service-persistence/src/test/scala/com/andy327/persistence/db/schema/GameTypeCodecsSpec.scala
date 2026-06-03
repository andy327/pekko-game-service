package com.andy327.persistence.db.schema

import java.util.UUID

import io.circe.parser._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.connectfour.{ConnectFour, Drop, Red}
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
      val Left(err) = result
      err.getMessage should include("Unknown GameType")
    }

    "deserialize a valid TicTacToe game from JSON" in {
      val game = TicTacToe.empty(alice, bob).play(X, Location(0, 0)).toOption.get
      val json = game.asJson.noSpaces
      val result = deserializeGame(GameType.TicTacToe, json)
      result shouldBe Right(game)
    }

    "fail to decode an unknown TicTacToe Mark type" in {
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

    "return Left if TicTacToe game JSON is invalid" in {
      val badJson = """{ "not": "valid" }"""

      val result = deserializeGame(GameType.TicTacToe, badJson)
      result.isLeft shouldBe true
    }

    "correctly encode and decode GameType.ConnectFour" in {
      val t: GameType = GameType.ConnectFour
      val json = t.asJson.noSpaces
      json shouldBe "\"ConnectFour\""

      val decoded = decode[GameType](json)
      decoded shouldBe Right(GameType.ConnectFour)
    }

    "deserialize a valid ConnectFour game from JSON" in {
      val game = ConnectFour.empty(alice, bob).play(Red, Drop(3)).toOption.get
      val json = game.asJson.noSpaces
      val result = deserializeGame(GameType.ConnectFour, json)
      result shouldBe Right(game)
    }

    "fail to decode an invalid ConnectFour Mark" in {
      val baseJson = ConnectFour.empty(alice, bob).asJson.noSpaces
      val corruptedJson = baseJson.replace("\"currentPlayer\":\"R\"", "\"currentPlayer\":\"Z\"")
      val result = deserializeGame(GameType.ConnectFour, corruptedJson)
      result.isLeft shouldBe true
    }

    "return Left if ConnectFour game JSON is invalid" in {
      val badJson = """{ "not": "valid" }"""
      val result = deserializeGame(GameType.ConnectFour, badJson)
      result.isLeft shouldBe true
    }
  }
}
