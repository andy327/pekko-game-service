package com.andy327.server.http.json

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import com.andy327.model.core.GameType
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.server.actors.core.GameManager.{ErrorResponse, LobbyCreated, LobbyJoined}
import com.andy327.server.http.json.GameStateConverters
import com.andy327.server.http.json.TicTacToeState._
import com.andy327.server.lobby._

class SerializationSpec extends AnyWordSpec with Matchers {
  import JsonProtocol._

  "UUID RootJsonFormat" should {
    "deserialize a valid UUID string" in {
      val uuid = UUID.randomUUID()
      JsString(uuid.toString).convertTo[UUID] shouldBe uuid
    }

    "fail on invalid UUID string" in {
      val invalid = JsString("not-a-uuid")
      val ex = intercept[DeserializationException] {
        invalid.convertTo[UUID]
      }
      ex.getMessage should include("Invalid UUID string")
    }

    "fail on non-string JSON value" in {
      val invalid = JsNumber(42)
      val ex = intercept[DeserializationException] {
        invalid.convertTo[UUID]
      }
      ex.getMessage should include("Expected UUID string")
    }
  }

  "Player JSON format" should {
    "round-trip serialize and deserialize" in {
      val player = Player(UUID.randomUUID(), "bob")
      val json = player.toJson
      json.convertTo[Player] shouldBe player
    }
  }

  "GameType RootJsonFormat" should {
    "deserialize a known game type" in {
      JsString("TicTacToe").convertTo[GameType] shouldBe GameType.TicTacToe
    }

    "fail on unknown game type string" in {
      val ex = intercept[DeserializationException] {
        JsString("UnimplementedGame").convertTo[GameType]
      }
      ex.getMessage should include("Unknown GameType")
    }

    "fail on non-string game type JSON" in {
      val ex = intercept[DeserializationException] {
        JsNumber(42).convertTo[GameType]
      }
      ex.getMessage should include("Expected GameType string")
    }
  }

  "GameLifecycleStatus RootJsonFormat" should {
    "serialize and deserialize all known values" in {
      val values: Seq[GameLifecycleStatus] = Seq(
        GameLifecycleStatus.WaitingForPlayers,
        GameLifecycleStatus.ReadyToStart,
        GameLifecycleStatus.InProgress,
        GameLifecycleStatus.Completed,
        GameLifecycleStatus.Cancelled
      )

      values.foreach { status =>
        val json = status.toJson
        json.convertTo[GameLifecycleStatus] shouldBe status
      }
    }

    "fail on unknown string" in {
      val ex = intercept[DeserializationException] {
        JsString("Paused").convertTo[GameLifecycleStatus]
      }
      ex.getMessage should include("Unknown GameLifecycleStatus")
    }

    "fail on non-string JSON" in {
      val ex = intercept[DeserializationException] {
        JsArray(Vector.empty).convertTo[GameLifecycleStatus]
      }
      ex.getMessage should include("Expected GameLifecycleStatus as string")
    }
  }

  "LobbyMetadata JSON format" should {
    "round-trip serialize and deserialize" in {
      val host = Player("host")
      val joiner = Player("guest")
      val metadata = LobbyMetadata(
        gameId = "game-id",
        gameType = GameType.TicTacToe,
        players = Map(host.id -> host, joiner.id -> joiner),
        hostId = host.id,
        status = GameLifecycleStatus.ReadyToStart
      )
      val json = metadata.toJson
      json.convertTo[LobbyMetadata] shouldBe metadata
    }
  }

  "LobbyCreated JSON format" should {
    "round-trip serialize and deserialize" in {
      val lobby = LobbyCreated("game-id", Player(UUID.randomUUID(), "host"))
      val json = lobby.toJson
      json.convertTo[LobbyCreated] shouldBe lobby
    }
  }

  "LobbyJoined JSON format" should {
    "round-trip serialize and deserialize" in {
      val host = Player("host")
      val joiner = Player("guest")
      val lobby = LobbyJoined(
        gameId = "game-id",
        metadata = LobbyMetadata(
          gameId = "game-id",
          gameType = GameType.TicTacToe,
          players = Map(host.id -> host, joiner.id -> joiner),
          hostId = host.id,
          status = GameLifecycleStatus.InProgress
        ),
        joinedPlayer = joiner
      )
      val json = lobby.toJson
      json.convertTo[LobbyJoined] shouldBe lobby
    }
  }

  "ErrorResponse JSON format" should {
    "round-trip serialize and deserialize" in {
      val error = ErrorResponse("Something went wrong")
      val json = error.toJson
      json.convertTo[ErrorResponse] shouldBe error
    }
  }

  "TicTacToeMove JSON format" should {
    "round-trip serialize and deserialize" in {
      val move = TicTacToeMove(row = 1, col = 2)
      val json = move.toJson
      json.convertTo[TicTacToeMove] shouldBe move
    }
  }

  "TicTacToeState view" should {
    "round-trip serialize and deserialize" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = TicTacToe.empty(alice.id, bob.id)
      val view = GameStateConverters.serializeGame(game)
      val json = view.toJson
      json.convertTo[TicTacToeState] shouldBe view
    }
  }
}
