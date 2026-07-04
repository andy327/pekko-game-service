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
import com.andy327.model.holdem.Action.{Bet, Call, Check, Fold}
import com.andy327.model.holdem.{Card, HandResult, HoldEmMove, PotAward, Street, TexasHoldEm}
import com.andy327.model.liarsdice.{Bid, Challenge, LiarsDice, MakeBid, StandingBid}
import com.andy327.model.mastermind.Peg.{Blue, Green, Red => MmRed, Yellow}
import com.andy327.model.mastermind.{Codebreaker, Codemaker, Guess, Mastermind, SetCode}
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

    "resolve \"pig\" via GameType.fromString" in {
      GameType.fromString("pig") shouldBe Some(GameType.Pig)
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

    "resolve mastermind via GameType.fromString" in {
      GameType.fromString("mastermind") shouldBe Some(GameType.Mastermind)
    }

    "correctly encode and decode GameType.Mastermind" in {
      val t: GameType = GameType.Mastermind
      val json = t.asJson.noSpaces
      json shouldBe "\"Mastermind\""
      decode[GameType](json) shouldBe Right(GameType.Mastermind)
    }

    "round-trip a Mastermind game through serializeGame and deserializeGame" in {
      // set the code then guess so the round-trip exercises the Peg and Attempt codecs
      val game = Mastermind
        .newGame(Seq(alice, bob))
        .play(Codemaker, SetCode(Vector(MmRed, Green, Yellow, Blue)))
        .flatMap(_.play(Codebreaker, Guess(Vector(MmRed, Green, Blue, Yellow))))
        .toOption
        .get
      val json = serializeGame(GameType.Mastermind, game)
      deserializeGame(GameType.Mastermind, json) shouldBe Right(game)
    }

    "round-trip finished Mastermind games, exercising the Role codec for both winners" in {
      // the winner is the only Role-typed field, so serialize a game won by each role to cover both codec branches
      val base = Mastermind
        .newGame(Seq(alice, bob))
        .play(Codemaker, SetCode(Vector(MmRed, Green, Yellow, Blue)))
        .toOption
        .get
      List(base.copy(winner = Some(Codebreaker)), base.copy(winner = Some(Codemaker))).foreach { game =>
        val json = serializeGame(GameType.Mastermind, game)
        deserializeGame(GameType.Mastermind, json) shouldBe Right(game)
      }
    }

    "fail to decode an invalid Mastermind Peg" in {
      val baseJson = Mastermind
        .newGame(Seq(alice, bob))
        .play(Codemaker, SetCode(Vector(MmRed, Green, Yellow, Blue)))
        .toOption
        .get
        .asJson
        .noSpaces
      val corruptedJson = baseJson.replace("\"red\"", "\"chartreuse\"")
      deserializeGame(GameType.Mastermind, corruptedJson).isLeft shouldBe true
    }

    "fail to decode an invalid Mastermind Role" in {
      val json = serializeGame(
        GameType.Mastermind,
        Mastermind
          .newGame(Seq(alice, bob))
          .play(Codemaker, SetCode(Vector(MmRed, Green, Yellow, Blue)))
          .toOption
          .get
          .copy(winner = Some(Codebreaker))
      )
      // the winner serializes as "codebreaker"; the field key "codebreakerId" is unaffected (no closing quote match)
      val corrupted = json.replace("\"codebreaker\"", "\"spymaster\"")
      deserializeGame(GameType.Mastermind, corrupted).isLeft shouldBe true
    }

    "return Left if Mastermind game JSON is invalid" in {
      deserializeGame(GameType.Mastermind, """{ "not": "valid" }""").isLeft shouldBe true
    }

    "resolve liarsdice via GameType.fromString" in {
      GameType.fromString("liarsdice") shouldBe Some(GameType.LiarsDice)
    }

    "correctly encode and decode GameType.LiarsDice" in {
      val t: GameType = GameType.LiarsDice
      val json = t.asJson.noSpaces
      json shouldBe "\"LiarsDice\""
      decode[GameType](json) shouldBe Right(GameType.LiarsDice)
    }

    "round-trip a Liar's Dice game through serializeGame and deserializeGame" in {
      // open with a numbered bid, then a wild "ones" bid, then a challenge, so the round-trip exercises the Bid
      // (with and without a face), StandingBid, and Reveal codecs
      val game = LiarsDice
        .newGame(Seq(alice, bob), List.fill(LiarsDice.MaxTotalDice)(4))
        .play(0, MakeBid(Bid(2, Some(4))))
        .flatMap(_.play(1, MakeBid(Bid(2, None)))) // "2 ones" is the next clockwise ones space after "2 fours"
        .flatMap(_.play(0, Challenge(List.fill(LiarsDice.MaxTotalDice)(3))))
        .toOption
        .get
      val json = serializeGame(GameType.LiarsDice, game)
      deserializeGame(GameType.LiarsDice, json) shouldBe Right(game)
    }

    "round-trip a finished Liar's Dice game with a standing bid still present" in {
      val game = LiarsDice.newGame(Seq(alice, bob), List.fill(LiarsDice.MaxTotalDice)(6))
        .copy(standing = Some(StandingBid(Bid(3, Some(5)), 0)), winner = Some(1))
      val json = serializeGame(GameType.LiarsDice, game)
      deserializeGame(GameType.LiarsDice, json) shouldBe Right(game)
    }

    "return Left if Liar's Dice game JSON is invalid" in {
      deserializeGame(GameType.LiarsDice, """{ "not": "valid" }""").isLeft shouldBe true
    }

    "resolve texasholdem via GameType.fromString" in {
      GameType.fromString("texasholdem") shouldBe Some(GameType.TexasHoldEm)
    }

    "correctly encode and decode GameType.TexasHoldEm" in {
      val t: GameType = GameType.TexasHoldEm
      val json = t.asJson.noSpaces
      json shouldBe "\"TexasHoldEm\""
      decode[GameType](json) shouldBe Right(GameType.TexasHoldEm)
    }

    "round-trip a Texas Hold 'Em game through serializeGame and deserializeGame" in {
      // play a hand to a showdown so the round-trip exercises the Card, Street, PotAward, and HandResult codecs (the
      // last carrying a shown-hands map and an odd-length board) alongside the full game state
      val deck = "AS AH KS KH AD 7C 2D 5S 9H".split(" ").map(Card(_)).toList
      val fullDeck = Card.deck
      val game = TexasHoldEm
        .newGame(Seq(alice, bob), deck)
        .play(0, HoldEmMove(Call, fullDeck))
        .flatMap(_.play(1, HoldEmMove(Check, fullDeck)))
        .flatMap(_.play(1, HoldEmMove(Check, fullDeck)))
        .flatMap(_.play(0, HoldEmMove(Bet(50), fullDeck)))
        .flatMap(_.play(1, HoldEmMove(Fold, fullDeck)))
        .toOption
        .get
      val json = serializeGame(GameType.TexasHoldEm, game)
      deserializeGame(GameType.TexasHoldEm, json) shouldBe Right(game)
    }

    "round-trip a finished Texas Hold 'Em game with a showdown result present" in {
      val game = TexasHoldEm
        .newGame(Seq(alice, bob), Card.deck)
        .copy(
          handResult = Some(
            HandResult(
              board = "AS KD 9C 4H 2S".split(" ").map(Card(_)).toList,
              shownHands = Map(0 -> List(Card("AH"), Card("AC")), 1 -> List(Card("KS"), Card("KH"))),
              awards = List(PotAward(200, List(0), Some("three of a kind, As")))
            )
          ),
          winner = Some(0)
        )
      val json = serializeGame(GameType.TexasHoldEm, game)
      deserializeGame(GameType.TexasHoldEm, json) shouldBe Right(game)
    }

    "round-trip a Texas Hold 'Em game on every street" in {
      val base = TexasHoldEm.newGame(Seq(alice, bob), Card.deck)
      Seq(Street.PreFlop, Street.Flop, Street.Turn, Street.River).foreach { street =>
        val game = base.copy(street = street)
        val json = serializeGame(GameType.TexasHoldEm, game)
        deserializeGame(GameType.TexasHoldEm, json) shouldBe Right(game)
      }
    }

    "fail to decode an unknown Street in Texas Hold 'Em JSON" in {
      val json = serializeGame(GameType.TexasHoldEm, TexasHoldEm.newGame(Seq(alice, bob), Card.deck))
      val corrupted = json.replace("\"street\":\"preflop\"", "\"street\":\"showdown\"")
      deserializeGame(GameType.TexasHoldEm, corrupted).isLeft shouldBe true
    }

    "fail to decode an invalid Card in Texas Hold 'Em JSON" in {
      val game = TexasHoldEm.newGame(Seq(alice, bob), Card.deck)
      val json = serializeGame(GameType.TexasHoldEm, game)
      // corrupt a hole card's rank to something outside 2–14
      val corrupted = json.replaceFirst("\"board\":\\[\"[^\"]+\"", "\"board\":[\"ZZ\"")
      deserializeGame(GameType.TexasHoldEm, corrupted).isLeft shouldBe true
    }

    "return Left if Texas Hold 'Em game JSON is invalid" in {
      deserializeGame(GameType.TexasHoldEm, """{ "not": "valid" }""").isLeft shouldBe true
    }
  }
}
