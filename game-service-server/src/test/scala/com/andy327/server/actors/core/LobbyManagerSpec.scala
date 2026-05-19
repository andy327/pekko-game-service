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

    "serve lobby metadata from cache after MarkCompleted removes it from active lobbies" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
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

      // lobby is no longer in active map, so it must not appear in ListLobbies
      lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      val listed = listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies
      listed.map(_.gameId) should not contain gameId

      // but GetLobbyInfo should still find it via the cache
      lm ! LobbyManager.GetLobbyInfo(gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Completed
    }

    "revert lobby status to WaitingForPlayers when a non-host player leaves" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      lm ! LobbyManager.LeaveLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyLeft]

      lm ! LobbyManager.GetLobbyInfo(gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "push LobbyUpdated to a subscriber when a player joins" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.SubscribeToLobby(gameId, subscriberProbe.ref)
      // immediate push of current state on subscribe
      subscriberProbe.expectMessageType[PlayerActor.SendEvent]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      val event = subscriberProbe.expectMessageType[PlayerActor.SendEvent]
      event.event shouldBe a[PlayerEvent.LobbyUpdated]
    }

    "push GameEnded(Cancelled) to subscribers when the host leaves" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.SubscribeToLobby(gameId, subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      lm ! LobbyManager.LeaveLobby(gameId, alice, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyLeft]

      val event = subscriberProbe.expectMessageType[PlayerActor.SendEvent]
      event.event shouldBe PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled)
    }

    "pass subscriber refs to SpawnGame and remove them from the subscriber map" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      lm ! LobbyManager.SubscribeToLobby(gameId, subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      lm ! LobbyManager.StartGame(gameId, host.id, responseProbe.ref)
      val spawnMsg = gmProbe.expectMessageType[GameManager.SpawnGame]
      spawnMsg.subscribers should contain(subscriberProbe.ref)

      // subscriber is removed from LobbyManager after handoff to game actor
      lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed]
      subscriberProbe.expectNoMessage()
    }

    "ignore MarkCompleted for an unknown lobby" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val unknownId = UUID.randomUUID()

      lm ! LobbyManager.MarkCompleted(unknownId, GameLifecycleStatus.Completed)

      // sanity check: LobbyManager is still responsive
      lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies shouldBe empty
    }

    "return pagination metadata with the lobby list" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")
      val carol = Player("carol")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, carol, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.ListLobbies(None, 1, 2, listProbe.ref)
      val page1 = listProbe.expectMessageType[LobbyManager.LobbiesListed]
      page1.lobbies should have size 2
      page1.page shouldBe 1
      page1.limit shouldBe 2
      page1.total shouldBe 3

      lm ! LobbyManager.ListLobbies(None, 2, 2, listProbe.ref)
      val page2 = listProbe.expectMessageType[LobbyManager.LobbiesListed]
      page2.lobbies should have size 1
      page2.total shouldBe 3
    }

    "filter lobbies by game type" in {
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val lm = spawn(LobbyManager(gmProbe.ref))
      val alice = Player("alice")
      val bob = Player("bob")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.ListLobbies(Some(GameType.TicTacToe), 1, 20, listProbe.ref)
      val result = listProbe.expectMessageType[LobbyManager.LobbiesListed]
      result.lobbies should have size 2
      result.total shouldBe 2
      result.lobbies.foreach(_.gameType shouldBe GameType.TicTacToe)
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
