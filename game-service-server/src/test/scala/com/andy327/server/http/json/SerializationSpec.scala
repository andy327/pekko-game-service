package com.andy327.server.http.json

import java.time.Instant
import java.util.UUID

import scala.util.Random

import io.circe.Json
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.core.GameManager.{LobbyCreated, LobbyJoined, LobbyLeft, MoveHistory, SubscribeAcknowledged}
import com.andy327.actor.core.PlayerEvent
import com.andy327.actor.game.{
  BattleshipState,
  GameState,
  GameStateConverters,
  HoldEmSeat,
  HoldEmState,
  LiarsDiceState,
  MastermindState,
  PigState
}
import com.andy327.actor.lobby._
import com.andy327.model.battleship.Battleship
import com.andy327.model.checkers.Checkers
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.GameType
import com.andy327.model.holdem.{Card, HandResult, PotAward, Street}
import com.andy327.model.liarsdice.Bid
import com.andy327.model.mastermind.{Attempt, Codebreaker, Feedback, Peg}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.MoveRecord
import com.andy327.server.http.auth.TokenResponse
import com.andy327.server.http.model.{ErrorResponse, MessageResponse}

/** Covers the codecs JsonProtocol owns: the API response types, the GridGameState view, and the write-only PlayerEvent
  * encoder. The reused value codecs (Player, GameType, GameLifecycleStatus, LobbyMetadata) are tested at their source
  * in LobbyCodecsSpec and GameTypeCodecsSpec.
  */
class SerializationSpec extends AnyWordSpec with Matchers {
  import JsonProtocol._

  "LobbyCreated codec" should {
    "round-trip serialize and deserialize" in {
      val lobby = LobbyCreated(UUID.randomUUID(), Player(UUID.randomUUID(), "host"))
      lobby.asJson.as[LobbyCreated] shouldBe Right(lobby)
    }
  }

  "LobbyJoined codec" should {
    "round-trip serialize and deserialize" in {
      val host = Player("host")
      val joiner = Player("guest")
      val roomId = UUID.randomUUID()
      val lobby = LobbyJoined(
        roomId = roomId,
        metadata = LobbyMetadata(
          roomId = roomId,
          gameType = GameType.TicTacToe,
          players = Map(host.id -> host, joiner.id -> joiner),
          hostId = host.id,
          status = GameLifecycleStatus.InProgress,
          createdAt = Instant.EPOCH
        ),
        joinedPlayer = joiner
      )
      lobby.asJson.as[LobbyJoined] shouldBe Right(lobby)
    }
  }

  "LobbyLeft codec" should {
    "round-trip serialize and deserialize" in {
      val lobby = LobbyLeft(UUID.randomUUID(), "Something went wrong")
      lobby.asJson.as[LobbyLeft] shouldBe Right(lobby)
    }
  }

  "ErrorResponse codec" should {
    "round-trip serialize and deserialize" in {
      val error = ErrorResponse("Something went wrong")
      error.asJson.as[ErrorResponse] shouldBe Right(error)
    }

    "serialize to an object with a single `error` field" in {
      ErrorResponse("nope").asJson shouldBe Json.obj("error" -> "nope".asJson)
    }
  }

  "MessageResponse codec" should {
    "serialize to an object with a single `message` field" in {
      MessageResponse("done").asJson shouldBe Json.obj("message" -> "done".asJson)
    }
  }

  "TokenResponse codec" should {
    "serialize to an object with a single `token` field" in {
      TokenResponse("jwt").asJson shouldBe Json.obj("token" -> "jwt".asJson)
    }
  }

  "SubscribeAcknowledged codec" should {
    "round-trip serialize and deserialize" in {
      val ack = SubscribeAcknowledged(UUID.randomUUID())
      ack.asJson.as[SubscribeAcknowledged] shouldBe Right(ack)
    }
  }

  "MoveHistory codec" should {
    "round-trip serialize and deserialize, preserving move payload and timestamp" in {
      val history = MoveHistory(
        UUID.randomUUID(),
        List(
          MoveRecord(0, UUID.randomUUID(), Json.obj("col" -> 3.asJson), Instant.parse("2026-06-14T12:00:00Z")),
          MoveRecord(1, UUID.randomUUID(), Json.obj("row" -> 1.asJson, "col" -> 2.asJson), Instant.EPOCH)
        )
      )
      history.asJson.as[MoveHistory] shouldBe Right(history)
    }

    "round-trip an empty history" in {
      val history = MoveHistory(UUID.randomUUID(), Nil)
      history.asJson.as[MoveHistory] shouldBe Right(history)
    }
  }

  // The grid view is write-only — see `gridGameStateEncoder` — so these cover the encoding direction only.
  // GameStateWireSpec pins the whole document; these cover the symbol rendering and the null handling the transport
  // depends on.
  "GridGameState encoder" should {
    "render marks by symbol, leaving unclaimed cells empty" in {
      val view = GameStateConverters.serializeGame(TicTacToe.empty(UUID.randomUUID(), UUID.randomUUID()), None)
      val cells = view.asJson.hcursor.downField("board").as[Vector[Vector[String]]]
      cells shouldBe Right(Vector.fill(3)(Vector.fill(3)("")))
    }

    "render a ConnectFour view through the same shared encoder" in {
      val view = GameStateConverters.serializeGame(ConnectFour.empty(UUID.randomUUID(), UUID.randomUUID()), None)
      view.asJson.hcursor.get[String]("currentPlayer") shouldBe Right("R")
    }

    "omit an absent winner rather than serializing it as null" in {
      val view = GameStateConverters.serializeGame(TicTacToe.empty(UUID.randomUUID(), UUID.randomUUID()), None)
      view.asJson.deepDropNullValues.asObject.flatMap(_("winner")) should not be defined
    }
  }

  "the polymorphic GameState encoder" should {
    "encode a BattleshipState branch (not just GridGameState)" in {
      val state: GameState =
        BattleshipState.of(Battleship.random(UUID.randomUUID(), UUID.randomUUID(), new Random(0)), None)
      val json = state.asJson
      json.hcursor.get[Option[String]]("viewerSeat") shouldBe Right(None)
      json.hcursor.downField("board1").focus should be(defined)
    }

    "encode a PigState branch" in {
      val state: GameState = PigState(
        scores = Vector(0, 0),
        currentPlayer = 0,
        turnScore = 0,
        lastRoll = None,
        winner = None,
        viewerSeat = None,
        legalMoves = Nil
      )
      val json = state.asJson
      json.hcursor.downField("scores").focus should be(defined)
      json.hcursor.get[String]("currentPlayer") shouldBe Right("P1")
    }

    "encode a MastermindState branch" in {
      val state: GameState = MastermindState(
        guesses = List(Attempt(Vector(Peg.Red, Peg.Green, Peg.Yellow, Peg.Blue), Feedback(black = 2, white = 1))),
        secret = None,
        currentPlayer = Codebreaker,
        winner = None,
        guessesRemaining = 9,
        viewerRole = Some(Codebreaker)
      )
      val json = state.asJson
      json.hcursor.downField("guesses").downN(0).get[List[String]]("pegs") shouldBe
        Right(List("red", "green", "yellow", "blue"))
      json.hcursor.get[String]("currentPlayer") shouldBe Right("codebreaker")
    }

    "encode a LiarsDiceState branch, keeping the viewer's dice and a wild-ones bid's absent face" in {
      val state: GameState = LiarsDiceState(
        dice = Some(Vector(2, 3, 4, 5, 6)),
        diceCounts = Vector(5, 5),
        currentBid = Some(Bid(2, None)), // a wild "ones" bid: face is absent
        currentPlayer = 0,
        winner = None,
        viewerSeat = Some(0),
        lastReveal = None,
        legalMoves = Nil
      )
      val json = state.asJson
      json.hcursor.get[List[Int]]("dice") shouldBe Right(List(2, 3, 4, 5, 6))
      json.hcursor.get[String]("currentPlayer") shouldBe Right("P1")
      json.hcursor.downField("currentBid").get[Option[Int]]("face") shouldBe Right(None)
    }

    "encode a HoldEmState branch, keeping the viewer's hole cards and the showdown reveal" in {
      val state: GameState = HoldEmState(
        seats = List(
          HoldEmSeat(990, 10, 10, folded = false, allIn = false),
          HoldEmSeat(985, 15, 15, folded = false, allIn = false)
        ),
        holeCards = Some(List(Card("AS"), Card("AH"))),
        board = List(Card("2C"), Card("7D"), Card("9S")),
        button = 0,
        currentPlayer = 1,
        currentBet = 15,
        minRaise = 30,
        pot = 25,
        toCall = 5,
        street = Street.Flop,
        winner = None,
        viewerSeat = Some(0),
        handResult = Some(
          HandResult(
            List(Card("2C")),
            Map(0 -> List(Card("AS"), Card("AH"))),
            List(PotAward(25, List(0), Some("one pair, As")))
          )
        ),
        legalMoves = Nil,
        betSizing = None
      )
      val json = state.asJson
      json.hcursor.get[List[String]]("holeCards") shouldBe Right(List("AS", "AH"))
      json.hcursor.get[String]("currentPlayer") shouldBe Right("P2")
      json.hcursor.get[Int]("toCall") shouldBe Right(5)
      json.hcursor.downField("handResult").downField("shownHands").get[List[String]]("P1") shouldBe Right(
        List("AS", "AH")
      )
    }

    "encode a CheckersState branch, carrying the board and the viewer's own seat" in {
      val red = UUID.randomUUID()
      val black = UUID.randomUUID()
      val state: GameState = GameStateConverters.serializeGame(Checkers.empty(red, black), Some(red)) // for Red
      val json = state.asJson
      json.hcursor.get[String]("viewerSeat") shouldBe Right("R")
      json.hcursor.get[String]("currentPlayer") shouldBe Right("R")
      json.hcursor.downField("board").downN(5).downN(0).as[String] shouldBe Right("r") // a Red pawn, un-crowned
    }
  }

  "playerEventEncoder" should {
    "encode LobbyUpdated with a type discriminator and metadata" in {
      val host = Player("host")
      val metadata = LobbyMetadata(
        roomId = UUID.randomUUID(),
        gameType = GameType.TicTacToe,
        players = Map(host.id -> host),
        hostId = host.id,
        status = GameLifecycleStatus.WaitingForPlayers,
        createdAt = Instant.EPOCH
      )
      val json = (PlayerEvent.LobbyUpdated(metadata, spectatorCount = 0): PlayerEvent).asJson
      json.hcursor.get[String]("type") shouldBe Right("LobbyUpdated")
      json.asObject.flatMap(_("metadata")) should be(defined)
      json.hcursor.get[Int]("spectatorCount") shouldBe Right(0)
    }

    "encode GameStateUpdated with a type discriminator, roomId, and state" in {
      val roomId = UUID.randomUUID()
      val state = GameStateConverters.serializeGame(TicTacToe.empty(UUID.randomUUID(), UUID.randomUUID()), None)
      val json = (PlayerEvent.GameStateUpdated(roomId, state, spectatorCount = 2): PlayerEvent).asJson
      json.hcursor.get[String]("type") shouldBe Right("GameStateUpdated")
      json.hcursor.get[UUID]("roomId") shouldBe Right(roomId)
      json.asObject.flatMap(_("state")) should be(defined)
      json.hcursor.get[Int]("spectatorCount") shouldBe Right(2)
    }

    "encode GameEnded with a type discriminator and result" in {
      val json = (PlayerEvent.GameEnded(GameLifecycleStatus.Completed): PlayerEvent).asJson
      json.hcursor.get[String]("type") shouldBe Right("GameEnded")
      json.hcursor.get[String]("result") shouldBe Right("Completed")
    }

    "encode ChatMessage with a type discriminator and fields" in {
      val event: PlayerEvent =
        PlayerEvent.ChatMessage(
          UUID.randomUUID(),
          UUID.randomUUID(),
          "alice",
          "gg",
          Instant.parse("2026-06-15T12:00:00Z")
        )
      val json = event.asJson
      json.hcursor.get[String]("type") shouldBe Right("ChatMessage")
      json.hcursor.get[String]("senderName") shouldBe Right("alice")
      json.hcursor.get[String]("text") shouldBe Right("gg")
      json.hcursor.get[String]("sentAt") shouldBe Right("2026-06-15T12:00:00Z")
    }
  }

  "ClientMessage decoder" should {
    "decode a ChatSend frame" in {
      val roomId = UUID.randomUUID()
      val json = s"""{"type":"ChatSend","roomId":"$roomId","text":"hello"}"""
      io.circe.parser.decode[ClientMessage](json) shouldBe Right(ClientMessage.ChatSend(roomId, "hello"))
    }

    "reject an unknown message type with an explanatory error" in {
      val error = io.circe.parser.decode[ClientMessage]("""{"type":"Nope"}""").swap.map(_.getMessage).getOrElse("")
      error should include("Unknown client message type: Nope")
    }

    "reject a frame missing the type discriminator" in {
      io.circe.parser.decode[ClientMessage]("""{"text":"no type"}""").isLeft shouldBe true
    }
  }
}
