package com.andy327.server.actors.core

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.GameType
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, Player}

class LobbyManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "LobbyManager" should {
    "create a lobby and return its metadata" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)

      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gameId.toString should not be empty
      host shouldBe alice
    }

    "forward a SpawnGame to GameManager when StartGame is valid" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      lm ! LobbyManager.StartGame(gameId, host.id, responseProbe.ref)

      val spawnMsg = gmProbe.expectMessageType[GameManager.SpawnGame]
      spawnMsg.gameId shouldBe gameId
      spawnMsg.gameType shouldBe GameType.TicTacToe
      (spawnMsg.players should contain).allOf(alice.id, bob.id)
    }

    "update lobby status to InProgress after a valid StartGame" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      lm ! LobbyManager.StartGame(gameId, host.id, responseProbe.ref)
      gmProbe.expectMessageType[GameManager.SpawnGame]

      lm ! LobbyManager.GetLobbyInfo(gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.InProgress
    }

    "update lobby status when MarkCompleted is received" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      lm ! LobbyManager.StartGame(gameId, host.id, responseProbe.ref)
      gmProbe.expectMessageType[GameManager.SpawnGame]

      lm ! LobbyManager.MarkCompleted(gameId, GameLifecycleStatus.Completed)

      lm ! LobbyManager.GetLobbyInfo(gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Completed
    }

    "ignore MarkCompleted for an unknown lobby" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val unknownId = UUID.randomUUID()

      lm ! LobbyManager.MarkCompleted(unknownId, GameLifecycleStatus.Completed)

      // sanity check: LobbyManager is still responsive
      lm ! LobbyManager.ListLobbies(responseProbe.ref)
      val GameManager.LobbiesListed(lobbies) = responseProbe.expectMessageType[GameManager.LobbiesListed]
      lobbies shouldBe empty
    }

    "return NotHostError when a non-host tries to start the game" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      val GameManager.LobbyJoined(_, _, joinedPlayer) = responseProbe.expectMessageType[GameManager.LobbyJoined]

      lm ! LobbyManager.StartGame(gameId, joinedPlayer.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.NotHostError(gameId)
      gmProbe.expectNoMessage()
    }

    "return LobbyNotReady when there are not enough players to start" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.StartGame(gameId, host.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotReady(gameId)
      gmProbe.expectNoMessage()
    }
  }
}
