package com.andy327.actor.core

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, LobbyRepository, Player}
import com.andy327.model.core.{GameType, RoomId}

class LobbyManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  implicit val runtime: IORuntime = IORuntime.global

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(roomId: RoomId): IO[Unit] = IO.unit
    override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
  }

  private val alice = Player("alice")
  private val bob = Player("bob")

  private case class LobbyFixture(
      lm: ActorRef[LobbyManager.Command],
      gmProbe: TestProbe[GameManager.Command],
      responseProbe: TestProbe[GameManager.GameResponse]
  )

  // Duration.Zero by default so tests can assert eviction without waiting out the real grace period; tests checking
  // that the grace period actually withholds eviction pass a long-enough override explicitly.
  private def newLobby(emptyRoomGrace: FiniteDuration = Duration.Zero): LobbyFixture = {
    val gmProbe = TestProbe[GameManager.Command]()
    val responseProbe = TestProbe[GameManager.GameResponse]()
    LobbyFixture(spawn(LobbyManager(gmProbe.ref, noOpLobbyRepo, emptyRoomGrace)), gmProbe, responseProbe)
  }

  /** Create a lobby with alice as host and have bob join. Returns (roomId, host). */
  private def createReadyLobby(f: LobbyFixture): (RoomId, Player) = {
    f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, f.responseProbe.ref)
    val GameManager.LobbyCreated(roomId, host) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]
    f.lm ! LobbyManager.JoinLobby(roomId, bob, f.responseProbe.ref)
    f.responseProbe.expectMessageType[GameManager.LobbyJoined]
    (roomId, host)
  }

  /** Create a ready lobby and start the game. Returns (roomId, spawnMsg) for further assertions. */
  private def startGame(f: LobbyFixture): (RoomId, GameManager.SpawnGame) = {
    val (roomId, host) = createReadyLobby(f)
    f.lm ! LobbyManager.StartGame(roomId, host.id, f.responseProbe.ref)
    val spawnMsg = f.gmProbe.expectMessageType[GameManager.SpawnGame]
    (roomId, spawnMsg)
  }

  "LobbyManager" should {
    "create a lobby and return its metadata" in {
      val LobbyFixture(lm, _, responseProbe) = newLobby()

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, responseProbe.ref)

      val GameManager.LobbyCreated(roomId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      roomId.toString should not be empty
      host shouldBe alice
    }

    "forward a SpawnGame to GameManager when StartGame is valid" in {
      val f = newLobby()
      val (roomId, spawnMsg) = startGame(f)

      spawnMsg.roomId shouldBe roomId
      spawnMsg.matchId should not be roomId // a fresh match id, distinct from the stable room id
      spawnMsg.gameType shouldBe GameType.TicTacToe
      (spawnMsg.players should contain).allOf(alice.id, bob.id)
    }

    "update lobby status to InProgress after a valid StartGame" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)

      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.InProgress
    }

    "seat the host first on the initial start" in {
      val f = newLobby()
      val (_, spawnMsg) = startGame(f)
      spawnMsg.players.head shouldBe alice.id // alice hosts; seat 0 moves first
    }

    "let the host start a rematch from a finished room with a fresh match id" in {
      val f = newLobby()
      val (roomId, spawn1) = startGame(f)
      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty) // the match ends → room becomes Finished

      f.lm ! LobbyManager.StartGame(roomId, alice.id, f.responseProbe.ref) // same StartGame command = rematch
      val spawn2 = f.gmProbe.expectMessageType[GameManager.SpawnGame]
      spawn2.roomId shouldBe roomId
      spawn2.matchId should not be spawn1.matchId
      spawn2.players.toSet shouldBe spawn1.players.toSet // same roster
    }

    "rotate the first-move seat on a rematch" in {
      val f = newLobby()
      val (roomId, spawn1) = startGame(f)
      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty)

      f.lm ! LobbyManager.StartGame(roomId, alice.id, f.responseProbe.ref)
      val spawn2 = f.gmProbe.expectMessageType[GameManager.SpawnGame]
      spawn1.players.head shouldBe alice.id // first match: host leads
      spawn2.players.head should not be alice.id // rematch: the seating rotates so the other player leads
    }

    "evict an empty finished room past its grace period and serve it from the recently-ended cache as Cancelled" in {
      val f = newLobby() // Duration.Zero grace: empty is immediately stale
      val (roomId, _) = startGame(f)
      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty) // Finished with no connected players

      f.lm ! LobbyManager.EvictIdleRooms
      f.gmProbe.expectMessage(GameManager.RoomClosed(roomId)) // lets GameManager drop its completedMatch entry

      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Cancelled
    }

    "not evict an empty finished room within its grace period" in {
      // a real-world momentary disconnect (e.g. a browser refresh) shouldn't cost the room on the very next tick
      val f = newLobby(emptyRoomGrace = 1.hour)
      val (roomId, _) = startGame(f)
      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty) // Finished with no connected players, activity = now

      f.lm ! LobbyManager.EvictIdleRooms

      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.Finished
    }

    "keep a finished room with a connected, recently-active player" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)
      val sub = TestProbe[PlayerActor.Command]()
      f.lm ! LobbyManager.MatchEnded(roomId, Map(alice.id -> sub.ref)) // re-owned subscriber, activity = now
      sub.expectMessageType[PlayerActor.SendEvent] // LobbyUpdated(Finished)

      f.lm ! LobbyManager.EvictIdleRooms

      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.Finished
    }

    "reject a rematch attempt from a non-host" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)
      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty)

      f.lm ! LobbyManager.StartGame(roomId, bob.id, f.responseProbe.ref) // bob is not the host
      f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse].error shouldBe LobbyError.NotHostError(roomId)
    }

    "move the room to Finished when its match ends" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)

      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty)

      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Finished
    }

    "keep a finished room listed (for spectating) and still queryable" in {
      val f = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val (roomId, _) = startGame(f)

      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty)

      // a Finished room isn't joinable, but it's still listed so a room doesn't flicker off the list between matches
      f.lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies.map(_.metadata.roomId) should contain(roomId)

      // and the room survives in memory (for chat/rematch), so GetLobbyInfo still finds it
      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Finished
    }

    "return match subscribers to the room so post-game chat reaches them" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)
      val sub = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.MatchEnded(roomId, Map(alice.id -> sub.ref))

      // the returned subscriber is told the room is now in its post-game (Finished) state
      sub.expectMessageType[PlayerActor.SendEvent].event match {
        case PlayerEvent.LobbyUpdated(m, _) => m.status shouldBe GameLifecycleStatus.Finished
        case other                          => fail(s"expected LobbyUpdated(Finished), got $other")
      }

      // and a chat broadcast to the room now reaches the returned subscriber (chat-after-end)
      val chat = PlayerEvent.ChatMessage(roomId, alice.id, "alice", "gg", Instant.now())
      f.lm ! LobbyManager.BroadcastChat(roomId, chat)
      sub.expectMessage(PlayerActor.SendEvent(chat))
    }

    "revert lobby status to WaitingForPlayers when a non-host player leaves" in {
      val f = newLobby()
      val (roomId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.LeaveLobby(roomId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]

      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "reject LeaveLobby with GameInProgress once the game has started" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)

      // a non-host player cannot leave an in-progress game
      f.lm ! LobbyManager.LeaveLobby(roomId, bob, f.responseProbe.ref)
      val nonHostError = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      nonHostError.error shouldBe LobbyError.GameInProgress(roomId)

      // neither can the host — the lobby must not be cancelled out from under the running game
      f.lm ! LobbyManager.LeaveLobby(roomId, alice, f.responseProbe.ref)
      val hostError = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      hostError.error shouldBe LobbyError.GameInProgress(roomId)

      // lobby is untouched: still present and still InProgress
      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = f.responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.InProgress
      (metadata.players.keySet should contain).allOf(alice.id, bob.id)
    }

    "not push events to a player after they leave the lobby" in {
      val f = newLobby()
      val carol = Player("carol")
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val (roomId, _) = createReadyLobby(f)

      // Bob subscribes to lobby events
      f.lm ! LobbyManager.SubscribeToLobby(roomId, bob.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial lobby state

      // Bob leaves — he receives LobbyUpdated for his own departure, then is removed from subscribers
      f.lm ! LobbyManager.LeaveLobby(roomId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // LobbyUpdated from own leave

      // Carol joins — lobby state changes, but Bob is no longer subscribed
      f.lm ! LobbyManager.JoinLobby(roomId, carol, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyJoined]

      subscriberProbe.expectNoMessage()
    }

    "fan a chat message out to lobby subscribers on BroadcastChat" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, f.responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial lobby state on subscribe

      val chat = PlayerEvent.ChatMessage(roomId, alice.id, "alice", "hi", Instant.EPOCH)
      f.lm ! LobbyManager.BroadcastChat(roomId, chat)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe chat
    }

    "push LobbyUpdated to a subscriber when a player joins" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, f.responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // immediate push of current state on subscribe

      f.lm ! LobbyManager.JoinLobby(roomId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyJoined]

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.LobbyUpdated]
    }

    "push GameEnded(Cancelled) to subscribers when the host leaves" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, f.responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      f.lm ! LobbyManager.LeaveLobby(roomId, alice, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled)
    }

    "migrate the host to a remaining member when the host leaves a populated lobby" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val (roomId, host) = createReadyLobby(f) // alice hosts, bob has joined

      f.lm ! LobbyManager.SubscribeToLobby(roomId, host.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      // host (alice) leaves while bob remains — the host role migrates to bob and the lobby survives
      f.lm ! LobbyManager.LeaveLobby(roomId, host, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft].message should include("host transferred")

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event match {
        case PlayerEvent.LobbyUpdated(meta, _) =>
          meta.hostId shouldBe bob.id
          meta.players.keySet shouldBe Set(bob.id)
        case other => fail(s"expected LobbyUpdated, got $other")
      }

      // the lobby is still present and queryable, now hosted by bob
      f.lm ! LobbyManager.GetLobbyInfo(roomId, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.hostId shouldBe bob.id
    }

    "pass subscriber refs to SpawnGame and remove them from the subscriber map" in {
      val f = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      val (roomId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      f.lm ! LobbyManager.StartGame(roomId, alice.id, f.responseProbe.ref)
      val spawnMsg = f.gmProbe.expectMessageType[GameManager.SpawnGame]
      spawnMsg.subscribers shouldBe Map(alice.id -> subscriberProbe.ref)

      // subscriber is removed from LobbyManager after handoff to game actor
      f.lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed]
      subscriberProbe.expectNoMessage()
    }

    "reject SubscribeToLobby with GameAlreadyStarted when the game is InProgress" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val (roomId, _) = startGame(f)

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, subscriberProbe.ref, f.responseProbe.ref)
      val error = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe a[LobbyError.GameAlreadyStarted]
      subscriberProbe.expectNoMessage()
    }

    "reject SubscribeToLobby with LobbyNotFound for an unknown game" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.SubscribeToLobby(UUID.randomUUID(), alice.id, subscriberProbe.ref, f.responseProbe.ref)
      val error = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe a[LobbyError.LobbyNotFound]
      subscriberProbe.expectNoMessage()
    }

    "stop pushing events to a subscriber after UnsubscribeFromLobby" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, f.responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial lobby state

      f.lm ! LobbyManager.UnsubscribeFromLobby(roomId, alice.id, f.responseProbe.ref)
      f.responseProbe.expectMessage(GameManager.UnsubscribeAcknowledged(roomId))

      // a later state change is no longer pushed to the unsubscribed player
      f.lm ! LobbyManager.JoinLobby(roomId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyJoined]
      subscriberProbe.expectNoMessage()
    }

    "acknowledge UnsubscribeFromLobby idempotently for an unknown lobby" in {
      val f = newLobby()
      val roomId = UUID.randomUUID()

      f.lm ! LobbyManager.UnsubscribeFromLobby(roomId, alice.id, f.responseProbe.ref)
      f.responseProbe.expectMessage(GameManager.UnsubscribeAcknowledged(roomId))
    }

    "cancel a lobby on the host's request and push GameEnded(Cancelled) to subscribers" in {
      val f = newLobby()
      val subscriberProbe = TestProbe[PlayerActor.Command]()
      val (roomId, host) = createReadyLobby(f)

      f.lm ! LobbyManager.SubscribeToLobby(roomId, bob.id, subscriberProbe.ref, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state

      f.lm ! LobbyManager.CancelLobby(roomId, host.id, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled)
      // sent even though this lobby never started a match — a harmless no-op for GameManager's completedMatch map
      f.gmProbe.expectMessage(GameManager.RoomClosed(roomId))

      // the lobby is gone from the joinable list
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      f.lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies shouldBe empty
    }

    "send RoomClosed when the host disbands a Finished room by leaving it last" in {
      val f = newLobby()
      val (roomId, _) = startGame(f)
      f.lm ! LobbyManager.MatchEnded(roomId, Map.empty)

      f.lm ! LobbyManager.LeaveLobby(roomId, bob, f.responseProbe.ref) // bob leaves first
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]

      f.lm ! LobbyManager.LeaveLobby(roomId, alice, f.responseProbe.ref) // host leaves last — disbands the room
      f.responseProbe.expectMessageType[GameManager.LobbyLeft]
      f.gmProbe.expectMessage(GameManager.RoomClosed(roomId))
    }

    "reject CancelLobby from a non-host with NotHostError" in {
      val f = newLobby()
      val (roomId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.CancelLobby(roomId, bob.id, f.responseProbe.ref)
      val error = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe a[LobbyError.NotHostError]
    }

    "reject CancelLobby for an unknown lobby with LobbyNotFound" in {
      val f = newLobby()

      f.lm ! LobbyManager.CancelLobby(UUID.randomUUID(), alice.id, f.responseProbe.ref)
      val error = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe a[LobbyError.LobbyNotFound]
    }

    "drop a subscriber whose session terminates without crashing on the death-watch" in {
      val f = newLobby()
      // a real actor we can stop to trigger a Terminated signal in the watching LobbyManager
      val dyingSubscriber = spawn(Behaviors.empty[PlayerActor.Command])

      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, f.responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.SubscribeToLobby(roomId, alice.id, dyingSubscriber, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.SubscribeAcknowledged]

      testKit.stop(dyingSubscriber)

      // without a Terminated handler the watching LobbyManager would crash with a DeathPactException; instead it stays
      // responsive and serves a subsequent command
      f.lm ! LobbyManager.JoinLobby(roomId, bob, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyJoined]
    }

    "restore in-progress lobbies from a RestoreLobbies message" in {
      val LobbyFixture(lm, _, responseProbe) = newLobby()
      val lobby = LobbyMetadata.newLobby(GameType.TicTacToe, alice).copy(status = GameLifecycleStatus.InProgress)

      lm ! LobbyManager.RestoreLobbies(List(lobby))

      lm ! LobbyManager.GetLobbyInfo(lobby.roomId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.roomId shouldBe lobby.roomId
      metadata.status shouldBe GameLifecycleStatus.InProgress
    }

    "drop and delete dead pre-game lobbies on restore, keeping only in-progress ones" in {
      val deleted = scala.collection.concurrent.TrieMap.empty[RoomId, Unit]
      val trackingRepo: LobbyRepository = new LobbyRepository {
        override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
        override def deleteLobby(roomId: RoomId): IO[Unit] = IO(deleted.update(roomId, ()))
        override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
      }
      val gmProbe = TestProbe[GameManager.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val lm = spawn(LobbyManager(gmProbe.ref, trackingRepo))

      val inProgress = LobbyMetadata.newLobby(GameType.TicTacToe, alice).copy(status = GameLifecycleStatus.InProgress)
      val waiting = LobbyMetadata.newLobby(GameType.TicTacToe, bob) // WaitingForPlayers — dead across a restart

      lm ! LobbyManager.RestoreLobbies(List(inProgress, waiting))

      // the in-progress lobby is restored and queryable
      lm ! LobbyManager.GetLobbyInfo(inProgress.roomId, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.InProgress

      // the pre-game lobby is gone from the active map and deleted from persistent storage
      lm ! LobbyManager.GetLobbyInfo(waiting.roomId, responseProbe.ref)
      val err = responseProbe.expectMessageType[GameManager.LobbyErrorResponse].error
      err shouldBe LobbyError.LobbyNotFound(waiting.roomId)
      responseProbe.awaitAssert(deleted.keySet should contain(waiting.roomId))
    }

    "list joinable lobbies newest-first" in {
      val LobbyFixture(lm, _, responseProbe) = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val carol = Player("carol")

      // created oldest-to-newest; the high-resolution creation clock makes each createdAt strictly later
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, bob, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, carol, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      val listed = listProbe.expectMessageType[LobbyManager.LobbiesListed]
      listed.lobbies.map(l => l.metadata.players(l.metadata.hostId).name) shouldBe List("carol", "bob", "alice")
    }

    "ignore MatchEnded for an unknown lobby" in {
      val LobbyFixture(lm, _, _) = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()

      lm ! LobbyManager.MatchEnded(UUID.randomUUID(), Map.empty)

      // sanity check: LobbyManager is still responsive
      lm ! LobbyManager.ListLobbies(None, 1, 20, listProbe.ref)
      listProbe.expectMessageType[LobbyManager.LobbiesListed].lobbies shouldBe empty
    }

    "return pagination metadata with the lobby list" in {
      val LobbyFixture(lm, _, responseProbe) = newLobby()
      val listProbe = TestProbe[LobbyManager.LobbiesListed]()
      val carol = Player("carol")

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, bob, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, carol, None, responseProbe.ref)
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

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]
      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, bob, None, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.ListLobbies(Some(GameType.TicTacToe), 1, 20, listProbe.ref)
      val result = listProbe.expectMessageType[LobbyManager.LobbiesListed]
      result.lobbies should have size 2
      result.total shouldBe 2
      result.lobbies.foreach(_.metadata.gameType shouldBe GameType.TicTacToe)
    }

    "list only the joinable lobbies a player has joined via ListLobbiesForPlayer" in {
      val f = newLobby()
      val carol = Player("carol")
      val playerLobbiesProbe = TestProbe[LobbyManager.PlayerLobbies]()

      // alice hosts a lobby bob joins (both are participants)
      val (joinedRoomId, _) = createReadyLobby(f)

      // a separate lobby hosted by carol that alice is not part of
      f.lm ! LobbyManager.CreateLobby(GameType.TicTacToe, carol, None, f.responseProbe.ref)
      f.responseProbe.expectMessageType[GameManager.LobbyCreated]

      f.lm ! LobbyManager.ListLobbiesForPlayer(alice.id, playerLobbiesProbe.ref)
      val aliceLobbies = playerLobbiesProbe.expectMessageType[LobbyManager.PlayerLobbies].lobbies
      aliceLobbies.map(_.roomId) should contain only joinedRoomId

      f.lm ! LobbyManager.ListLobbiesForPlayer(bob.id, playerLobbiesProbe.ref)
      playerLobbiesProbe.expectMessageType[LobbyManager.PlayerLobbies].lobbies.map(_.roomId) should contain only
        joinedRoomId
    }

    "exclude in-progress lobbies from ListLobbiesForPlayer" in {
      val f = newLobby()
      val playerLobbiesProbe = TestProbe[LobbyManager.PlayerLobbies]()
      val (roomId, _) = startGame(f) // alice + bob, now InProgress

      // the InProgress lobby still lives in the map, but is not a pre-game lobby and must not be reported here
      f.lm ! LobbyManager.ListLobbiesForPlayer(alice.id, playerLobbiesProbe.ref)
      playerLobbiesProbe.expectMessageType[LobbyManager.PlayerLobbies].lobbies.map(_.roomId) should not contain roomId
    }

    "return an empty list from ListLobbiesForPlayer for a player in no lobbies" in {
      val f = newLobby()
      val playerLobbiesProbe = TestProbe[LobbyManager.PlayerLobbies]()
      createReadyLobby(f) // alice + bob, but we query a stranger

      f.lm ! LobbyManager.ListLobbiesForPlayer(Player("stranger").id, playerLobbiesProbe.ref)
      playerLobbiesProbe.expectMessageType[LobbyManager.PlayerLobbies].lobbies shouldBe empty
    }

    "return NotHostError when a non-host tries to start the game" in {
      val f = newLobby()
      val (roomId, _) = createReadyLobby(f)

      f.lm ! LobbyManager.StartGame(roomId, bob.id, f.responseProbe.ref)
      val error = f.responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.NotHostError(roomId)
      f.gmProbe.expectNoMessage()
    }

    "return LobbyNotReady when there are not enough players to start" in {
      val LobbyFixture(lm, gmProbe, responseProbe) = newLobby()

      lm ! LobbyManager.CreateLobby(GameType.TicTacToe, alice, None, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      lm ! LobbyManager.StartGame(roomId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotReady(roomId)
      gmProbe.expectNoMessage()
    }
  }
}
