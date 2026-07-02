package com.andy327.server.http.json

import java.time.Instant
import java.util.UUID

import scala.util.Random

import io.circe.Json
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.core.GameManager.{
  ErrorResponse,
  LobbyCreated,
  LobbyJoined,
  LobbyLeft,
  MoveHistory,
  SubscribeAcknowledged
}
import com.andy327.actor.core.PlayerEvent
import com.andy327.actor.game.{
  BattleshipState,
  GameState,
  GameStateConverters,
  GridGameState,
  GuessResult,
  MastermindState,
  PigState
}
import com.andy327.actor.lobby._
import com.andy327.model.battleship.Battleship
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.GameType
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.MoveRecord

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

  "GridGameState codec" should {
    "round-trip a TicTacToe view" in {
      val view = GameStateConverters.serializeGame(TicTacToe.empty(UUID.randomUUID(), UUID.randomUUID()), None)
      view.asJson.as[GridGameState] shouldBe Right(view)
    }

    "round-trip a ConnectFour view" in {
      val view = GameStateConverters.serializeGame(ConnectFour.empty(UUID.randomUUID(), UUID.randomUUID()), None)
      view.asJson.as[GridGameState] shouldBe Right(view)
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
        scores = Map("P1" -> 0, "P2" -> 0),
        currentPlayer = "P1",
        turnScore = 0,
        lastRoll = None,
        winner = None,
        viewerSeat = None
      )
      val json = state.asJson
      json.hcursor.downField("scores").focus should be(defined)
      json.hcursor.get[String]("currentPlayer") shouldBe Right("P1")
    }

    "encode a MastermindState branch" in {
      val state: GameState = MastermindState(
        guesses = List(GuessResult(List("red", "green", "yellow", "blue"), 2, 1)),
        secret = None,
        currentPlayer = "codebreaker",
        winner = None,
        guessesRemaining = 9,
        viewerRole = Some("codebreaker")
      )
      val json = state.asJson
      json.hcursor.downField("guesses").focus should be(defined)
      json.hcursor.get[String]("currentPlayer") shouldBe Right("codebreaker")
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
