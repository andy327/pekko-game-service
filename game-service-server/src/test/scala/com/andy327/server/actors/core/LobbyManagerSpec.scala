package com.andy327.server.actors.core

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{GameId, GameType}
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, Player}

class LobbyManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  private val alice = Player("alice")
  private val bob = Player("bob")

  private case class LobbyFixture(
      lm: ActorRef[LobbyManager.Command],
      gmProbe: TestProbe[GameManager.Command],
      responseProbe: TestProbe[GameManager.GameResponse]
  )

  private def newLobby(): LobbyFixture = {
    val gmProbe = TestProbe[GameManager.Command]()
    val responseProbe = TestProbe[GameManager.GameResponse]()
    LobbyFixture(spawn(LobbyManager(gmProbe.ref)), gmProbe, responseProbe)
  }

  /** Create a lobby with alice as host and have bob join. Returns (gameId, host). */
  private def createReadyLobby(f: LobbyFixture): (GameId, Player) = {
    f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, f.responseProbe.ref)
    val GameManager.LobbyCreated(gameId, host) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]
    f.lm ! LobbyManager.JoinLobby(gameId, bob, f.responseProbe.ref)
    f.responseProbe.expectMessageType[GameManager.LobbyJoined]
    (gameId, host)
  }

  /** Create a ready lobby and start the game. Returns (gameId, spawnMsg) for further assertions. */
  private def startGame(f: LobbyFixture): (GameId, GameManager.SpawnGame) = {
    val (gameId, host) = createReadyLobby(f)
    f.lm ! LobbyManager.StartGame(gameId, host.id, f.responseProbe.ref)
    val spawnMsg = f.gmProbe.expectMessageType[GameManager.SpawnGame]
    (gameId, spawnMsg)
  }

  "LobbyManager" should {
    "create a lobby and return its metadata" in {
      val LobbyFixture(lm, _, responseProbe) = newLobby()

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)

      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gameId.toString should not be empty
      host shouldBe alice
    }

    "forward a SpawnGame to GameManager when StartGame is valid" in {
      val f = newLobby()
      val (gameId, spawnMsg) = startGame(f)

      spawnMsg.gameId shouldBe gameId
      spawnMsg.gameType shouldBe GameType.TicTacToe
      (spawnMsg.players should contain).allOf(alice.id, bob.id)
    }

    "update lobby status to InProgress after a valid StartGame" in {
      val f = newLobby()
      val (gameId, _) = startGame(f)

      f.lm ! LobbyManager.GetLobbyInfo(gameId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.InProgress
    }

    "update lobby status when MarkCompleted is received" in {
      val f = newLobby()
      val (gameId, _) = startGame(f)

      f.lm ! LobbyManager.MarkCompleted(gameId, GameLifecycleStatus.Completed)

      f.lm ! LobbyManager.GetLobbyInfo(gameId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Completed
    }

    "serve lobby metadata from cache after MarkCompleted removes it from active lobbies" in {
      val f = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val (gameId, _) = startGame(f)

      f.lm ! LobbyManager.MarkCompleted(gameId, GameLifecycleStatus.Completed)

      // lobby is no longer in active map, so it must not appear in ListLobbies
      f.lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies.map(_.gameId) should not contain gameId

      // but GetLobbyInfo should still find it via the cache
      f.lm ! LobbyManager.GetLobbyInfo(gameId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Completed
    }

    "revert lobby status to WaitingForPlayers when a non-host player leaves" in {
      val f = newLobby()
      val (gameId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.LeaveLobby(gameId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]

      f.lm ! LobbyManager.GetLobbyInfo(gameId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "push LobbyUpdated to a subscriber when a player joins" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, f.responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(gameId, subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // immediate push of current state on subscribe

      f.lm ! LobbyManager.JoinLobby(gameId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyJoined]

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.LobbyUpdated]
    }

    "push GameEnded(Cancelled) to subscribers when the host leaves" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, f.responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(gameId, subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      f.lm ! LobbyManager.LeaveLobby(gameId, alice, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled)
    }

    "pass subscriber refs to SpawnGame and remove them from the subscriber map" in {
      val f = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      val (gameId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.SubscribeToLobby(gameId, subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      f.lm ! LobbyManager.StartGame(gameId, alice.id, f.responseProbe.ref)
      val spawnMsg = f.gmProbe.expectMessageType[GameManager.SpawnGame]
      spawnMsg.subscribers should contain(subscriberProbe.ref)

      // subscriber is removed from LobbyManager after handoff to game actor
      f.lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed]
      subscriberProbe.expectNoMessage()
    }

    "ignore MarkCompleted for an unknown lobby" in {
      val LobbyFixture(lm, _, _) = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()

      lm ! LobbyManager.MarkCompleted(UUID.randomUUID(), GameLifecycleStatus.Completed)

      // sanity check: LobbyManager is still responsive
      lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies shouldBe empty
    }

    "return pagination metadata with the lobby list" in {
      val LobbyFixture(lm, _, responseProbe) = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
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
      val LobbyFixture(lm, _, responseProbe) = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()

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
      val f = newLobby()
      val (gameId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.StartGame(gameId, bob.id, f.responseProbe.ref)
      val error = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.NotHostError(gameId)
      f.gmProbe.expectNoMessage()
    }

    "return LobbyNotReady when there are not enough players to start" in {
      val LobbyFixture(lm, gmProbe, responseProbe) = newLobby()

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.StartGame(gameId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotReady(gameId)
      gmProbe.expectNoMessage()
    }
  }
}
