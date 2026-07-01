package com.andy327.persistence.db.schema

import java.util.UUID

import scala.util.Random

import io.circe.parser._
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.battleship.{Battleship, Coord, Fire, Player1, Player2, PlayerBoard, Ship}
import com.andy327.model.connectfour.{ConnectFour, Drop, Red}
import com.andy327.model.core.{GameType, PlayerId}
import com.andy327.model.pig.Pig
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

    "round-trip a TicTacToe game through serializeGame and deserializeGame" in {
      val game = TicTacToe.empty(alice, bob).play(X, Location(0, 0)).toOption.get
      val json = serializeGame(GameType.TicTacToe, game)
      deserializeGame(GameType.TicTacToe, json) shouldBe Right(game)
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

    "round-trip a ConnectFour game through serializeGame and deserializeGame" in {
      val game = ConnectFour.empty(alice, bob).play(Red, Drop(3)).toOption.get
      val json = serializeGame(GameType.ConnectFour, game)
      deserializeGame(GameType.ConnectFour, json) shouldBe Right(game)
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

    "correctly encode and decode GameType.Battleship" in {
      val t: GameType = GameType.Battleship
      val json = t.asJson.noSpaces
      json shouldBe "\"Battleship\""

      val decoded = decode[GameType](json)
      decoded shouldBe Right(GameType.Battleship)
    }

    "round-trip a Battleship game through serializeGame and deserializeGame" in {
      val game = Battleship.random(alice, bob, new Random(1)).play(Player1, Fire(Coord(0, 0))).toOption.get
      val json = serializeGame(GameType.Battleship, game)
      deserializeGame(GameType.Battleship, json) shouldBe Right(game)
    }

    "round-trip a finished Battleship game, decoding both Player1 and Player2 seats" in {
      // winner serializes as "P1" and currentPlayer as "P2", so the round-trip exercises both Seat decode branches
      val game = Battleship(
        alice,
        bob,
        PlayerBoard(List(Ship(Set(Coord(0, 0)))), Set.empty),
        PlayerBoard(List(Ship(Set(Coord(0, 0)))), Set(Coord(0, 0))),
        currentPlayer = Player2,
        winner = Some(Player1)
      )
      val json = serializeGame(GameType.Battleship, game)
      deserializeGame(GameType.Battleship, json) shouldBe Right(game)
    }

    "fail to decode an invalid Battleship Seat" in {
      val baseJson = Battleship.random(alice, bob, new Random(1)).asJson.noSpaces
      val corruptedJson = baseJson.replace("\"currentPlayer\":\"P1\"", "\"currentPlayer\":\"Z\"")
      val result = deserializeGame(GameType.Battleship, corruptedJson)
      result.isLeft shouldBe true
    }

    "return Left if Battleship game JSON is invalid" in {
      val badJson = """{ "not": "valid" }"""
      val result = deserializeGame(GameType.Battleship, badJson)
      result.isLeft shouldBe true
    }

    "correctly encode and decode GameType.Pig" in {
      val t: GameType = GameType.Pig
      val json = t.asJson.noSpaces
      json shouldBe "\"Pig\""
      decode[GameType](json) shouldBe Right(GameType.Pig)
    }

    "round-trip a Pig game through serializeGame and deserializeGame" in {
      val game = Pig.newGame(Seq(alice, bob))
      val json = serializeGame(GameType.Pig, game)
      deserializeGame(GameType.Pig, json) shouldBe Right(game)
    }

    "return Left if Pig game JSON is invalid" in {
      val result = deserializeGame(GameType.Pig, """{ "not": "valid" }""")
      result.isLeft shouldBe true
    }
  }
}
