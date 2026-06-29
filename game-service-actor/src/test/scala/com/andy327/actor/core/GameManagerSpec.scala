package com.andy327.actor.core

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.concurrent.Eventually
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.chat.ChatRepository
import com.andy327.actor.core.{PlayerActor, PlayerEvent}
import com.andy327.actor.events.{EventPublisher, GameEvent}
import com.andy327.actor.game.{GameOperation, GameStateConverters, GridGameState, MovePayload}
import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, LobbyRepository, Player}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{Game, GameType, MatchId, PlayerId, RoomId}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.{GameRepository, MoveHistoryRepository, MoveRecord}

/** In-memory GameRepository for unit tests */
class InMemRepo(initialGames: Map[MatchId, (GameType, Game[_, _, _, _, _])] = Map.empty) extends GameRepository {
  private val db = scala.collection.concurrent.TrieMap(initialGames.toSeq: _*)

  def initialize(): IO[Unit] = IO.unit

  def saveGame(id: MatchId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] =
    IO(db.update(id, (tpe, g)))

  def loadGame(id: MatchId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] =
    IO(db.get(id).collect { case (`tpe`, g) => g })

  def loadAllGames(): IO[Map[MatchId, (GameType, Game[_, _, _, _, _])]] =
    IO(db.toMap)
}

/** In-memory MoveHistoryRepository for unit tests; `loadFails` forces loadMoves to raise. */
class InMemMoveRepo(initial: Map[MatchId, List[MoveRecord]] = Map.empty, loadFails: Boolean = false)
    extends MoveHistoryRepository {
  private val db = scala.collection.concurrent.TrieMap(initial.toSeq: _*)

  def initialize(): IO[Unit] = IO.unit

  def appendMove(matchId: MatchId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] =
    IO(db.update(matchId, db.getOrElse(matchId, Nil) :+ MoveRecord(seq, playerId, move, Instant.EPOCH)))

  def loadMoves(matchId: MatchId): IO[List[MoveRecord]] =
    if (loadFails) IO.raiseError(new RuntimeException("move load failure") with NoStackTrace)
    else IO.pure(db.getOrElse(matchId, Nil))
}

/** In-memory ChatRepository for unit tests; records appends and `loadFails` forces recent to raise. */
class InMemChatRepo(initial: Map[RoomId, List[PlayerEvent.ChatMessage]] = Map.empty, loadFails: Boolean = false)
    extends ChatRepository {
  private val db = scala.collection.concurrent.TrieMap(initial.toSeq: _*)

  def append(message: PlayerEvent.ChatMessage): IO[Unit] =
    IO(db.update(message.roomId, db.getOrElse(message.roomId, Nil) :+ message))

  def recent(roomId: RoomId): IO[List[PlayerEvent.ChatMessage]] =
    if (loadFails) IO.raiseError(new RuntimeException("chat load failure") with NoStackTrace)
    else IO.pure(db.getOrElse(roomId, Nil))
}

class GameManagerSpec extends AnyWordSpecLike with Matchers with Eventually {
  private val testKit = ActorTestKit()
  import testKit._

  implicit val runtime: IORuntime = IORuntime.global

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(roomId: RoomId): IO[Unit] = IO.unit
    override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
  }

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  "GameManager" should {
    "handle database failure gracefully on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val failingRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: MatchId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: MatchId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[MatchId, (GameType, Game[_, _, _, _, _])]] =
          IO.raiseError(new RuntimeException("DB failure") with NoStackTrace)
      }

      val _ = spawn(GameManager(persistProbe.ref, failingRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready)
    }

    "stash messages during initialization and process them after RestoreGames" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val slowRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: MatchId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: MatchId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[MatchId, (GameType, Game[_, _, _, _, _])]] = IO.sleep(1.second) *> IO.pure(Map.empty)
      }
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, slowRepo, noOpLobbyRepo))

      // Send a command that would be stashed during initialization
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)

      // Initially, no response because it's still initializing
      responseProbe.expectNoMessage(500.millis)

      // Eventually, after restore, the stashed message is processed
      val response = responseProbe.expectMessageType[GameManager.LobbyCreated](2.seconds)
      response.roomId.toString should not be empty
    }

    "restore saved games from the game repository on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val roomId: RoomId = UUID.randomUUID()
      val restoredGame = TicTacToe.empty(alice, bob)
      val gameRepo = new InMemRepo(Map(roomId -> (GameType.TicTacToe, restoredGame)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val gameResponseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.RunGameOperation(roomId, GameOperation.GetState, gameResponseProbe.ref)
      val response = gameResponseProbe.expectMessageType[GameManager.GameResponse]
      response shouldBe GameManager.GameStatus(GameStateConverters.serializeGame(restoredGame, None))
    }

    "ignore RestoreGames messages in running state" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Now send an unexpected RestoreGames message while in `running` state
      gm ! GameManager.RestoreGames(Map.empty, Nil)

      // Sanity check: send a valid command and expect the proper response
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)

      val response = responseProbe.expectMessageType[GameManager.LobbiesListed]
      assert(response.lobbies.isEmpty)
    }

    "create a new lobby and return its metadata" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)

      val response = responseProbe.expectMessageType[GameManager.LobbyCreated]
      response.roomId.toString should not be empty

      gm ! GameManager.GetLobbyInfo(response.roomId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]

      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "return an error when retrieving metadata for a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonexistentRoomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetLobbyInfo(nonexistentRoomId, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentRoomId)
    }

    "allow a second player to join a lobby and change its status to ReadyToStart" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.LobbyJoined]
      response.metadata.status shouldBe GameLifecycleStatus.ReadyToStart
    }

    "prevent a player from joining a lobby again" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      gm ! GameManager.JoinLobby(roomId, alice, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.AlreadyInLobby(roomId)

      gm ! GameManager.GetLobbyInfo(roomId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]

      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "prevent a player from joining a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentRoomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.JoinLobby(nonexistentRoomId, alice, responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentRoomId)
    }

    "prevent too many players from joining a lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val carl = Player("carl")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // Alice creates the lobby
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Carl tries to join - should be rejected
      gm ! GameManager.JoinLobby(roomId, carl, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyFull(roomId)
    }

    "prevent a player from joining a lobby of a game that's already started" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val carl = Player("carl")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // Alice creates the lobby
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Game bgeins
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Carl tries to join - should be rejected
      gm ! GameManager.JoinLobby(roomId, carl, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotJoinable(roomId)
    }

    "allow a host to start a game from a ready lobby and persist the snapshot" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.GameStarted(roomId))

      val snapshot = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      // the snapshot is keyed by the match id (minted fresh, distinct from the stable room id)
      snapshot.matchId should not be roomId
      snapshot.gameType shouldBe GameType.TicTacToe
    }

    "prevent a non-host player from starting the game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      val GameManager.LobbyJoined(_, _, joinedPlayer) = responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Bob tries to start the game
      gm ! GameManager.StartGame(roomId, joinedPlayer.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.NotHostError(roomId)
    }

    "prevent a player from starting a nonexistent game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentRoomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.StartGame(nonexistentRoomId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentRoomId)
    }

    "list available lobbies" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // Game 1: InProgress
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId1, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId1, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId1, alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Game 2: ReadyToStart
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId2, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId2, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Game 2: WaitingForPlayers
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId3, _) = responseProbe.receiveMessage()

      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.LobbiesListed]
      response.lobbies.map(_.metadata.roomId) should contain only (roomId2, roomId3)
    }

    "report a player's joined lobbies and active games via GetPlayerSessions" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      // Lobby A: alice + bob, left in a pre-game (joinable) state
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(lobbyA, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(lobbyA, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Lobby B: alice + bob, started — now a live game
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameB, hostB) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameB, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameB, hostB.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      gm ! GameManager.GetPlayerSessions(alice.id, responseProbe.ref)
      val sessions = responseProbe.expectMessageType[GameManager.PlayerSessions]
      sessions.lobbies.map(_.roomId) should contain only lobbyA
      sessions.games should contain only GameManager.ActiveGameSummary(gameB, GameType.TicTacToe)
    }

    "drop a completed game from GetPlayerSessions" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      gm ! GameManager.GetPlayerSessions(alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.PlayerSessions].games.map(_.roomId) should contain(roomId)

      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      gm ! GameManager.GetPlayerSessions(alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.PlayerSessions].games shouldBe empty
    }

    "rebuild the active-game index for restored games so GetPlayerSessions survives a restart" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val game = TicTacToe.empty(alice, bob)
      val gameRepo = new InMemRepo(Map(roomId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.GetPlayerSessions(alice, responseProbe.ref)
      val sessions = responseProbe.expectMessageType[GameManager.PlayerSessions]
      sessions.games should contain only GameManager.ActiveGameSummary(roomId, GameType.TicTacToe)
    }

    "return empty sessions for a player who is in nothing" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetPlayerSessions(UUID.randomUUID(), responseProbe.ref)
      val sessions = responseProbe.expectMessageType[GameManager.PlayerSessions]
      sessions.lobbies shouldBe empty
      sessions.games shouldBe empty
    }

    "return the recorded move history for a game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val moveRepo = new InMemMoveRepo()
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, moveRepo = moveRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      // start a game so the room resolves to a live match (the move log is keyed by match id, not room id)
      gm ! GameManager.CreateLobby(GameType.TicTacToe, Player("alice"), responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId, Player("bob"), responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.GameStarted(roomId))
      val matchId = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot].matchId

      // seed the move log under the match id the room now points at
      moveRepo.appendMove(matchId, 0, alice, Json.obj("col" -> 3.asJson)).unsafeRunSync()
      moveRepo.appendMove(matchId, 1, bob, Json.obj("col" -> 4.asJson)).unsafeRunSync()
      val moves = List(
        MoveRecord(0, alice, Json.obj("col" -> 3.asJson), Instant.EPOCH),
        MoveRecord(1, bob, Json.obj("col" -> 4.asJson), Instant.EPOCH)
      )

      gm ! GameManager.GetMoveHistory(roomId, responseProbe.ref)
      responseProbe.expectMessage(GameManager.MoveHistory(roomId, moves))
    }

    "return an empty move history for a game with no recorded moves" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val roomId: RoomId = UUID.randomUUID()
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, moveRepo = new InMemMoveRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.GetMoveHistory(roomId, responseProbe.ref)

      responseProbe.expectMessage(GameManager.MoveHistory(roomId, Nil))
    }

    "return an error when the move history load fails" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val moveRepo = new InMemMoveRepo(loadFails = true)
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, moveRepo = moveRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.GetMoveHistory(UUID.randomUUID(), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("Failed to retrieve move history")
    }

    "move the room to Finished when receiving GameCompleted message" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // game actor sends a GameCompleted message to the GameManager
      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      // the room survives the match as a post-game room, now in the Finished state
      gm ! GameManager.GetLobbyInfo(roomId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Finished
    }

    "move a restored room to Finished when its restored game finishes" in {
      // Simulates a restart mid-game: the game comes back from the game repository and its
      // InProgress lobby comes back from the lobby repository. When the game later completes,
      // the room must move to Finished and its stale in-progress record be deleted from persistent storage.
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val host = Player("alice")
      val guest = Player("bob")
      val game = TicTacToe.empty(host.id, guest.id)
      val gameRepo = new InMemRepo(Map(roomId -> (GameType.TicTacToe, game)))

      val restoredLobby = LobbyMetadata(
        roomId,
        GameType.TicTacToe,
        Map(host.id -> host, guest.id -> guest),
        host.id,
        GameLifecycleStatus.InProgress,
        Instant.now(),
        currentMatchId = Some(roomId) // links the restored room to its in-progress match (keyed by roomId in gameRepo)
      )
      val deletedLobbies = scala.collection.concurrent.TrieMap.empty[RoomId, Unit]
      val restoringLobbyRepo: LobbyRepository = new LobbyRepository {
        override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
        override def deleteLobby(roomId: RoomId): IO[Unit] = IO(deletedLobbies.update(roomId, ()))
        override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(List(restoredLobby))
      }

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, restoringLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // sanity check: the restored lobby is known and InProgress
      gm ! GameManager.GetLobbyInfo(roomId, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.InProgress

      // the restored game completes
      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      gm ! GameManager.GetLobbyInfo(roomId, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.Finished

      // the fire-and-forget Redis delete is eventually dispatched
      responseProbe.awaitAssert(deletedLobbies.keySet should contain(roomId))
    }

    "serve game state from DB after the game actor is stopped on completion" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(roomId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(roomId, GameOperation.GetState, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.GameStatus]
      response.state shouldBe GameStateConverters.serializeGame(game, None)
    }

    "drop the completedMatch entry on RoomClosed, so a later operation reports GameNotFound" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(roomId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)
      // sanity check: the completedMatch entry serves GetState from the DB while the room is still known
      gm ! GameManager.RunGameOperation(roomId, GameOperation.GetState, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStatus]

      gm ! GameManager.RoomClosed(roomId) // the room is retired for good (cancelled or evicted)

      gm ! GameManager.RunGameOperation(roomId, GameOperation.GetState, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameNotFound].roomId shouldBe roomId
    }

    "return an error when forwarding a move to a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(roomId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(
        roomId,
        GameOperation.MakeMove(playerX, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      val error = responseProbe.expectMessageType[GameManager.MoveRejected]
      error.message should include("Game has already ended")
    }

    "return GameNotFound when DB has no record for a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: MatchId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: MatchId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[MatchId, (GameType, Game[_, _, _, _, _])]] =
          IO.pure(Map(roomId -> (GameType.TicTacToe, game)))
      }

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(roomId, GameOperation.GetState, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameNotFound].roomId shouldBe roomId
    }

    "return an error when the DB call fails for a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val roomId: RoomId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: MatchId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: MatchId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] =
          IO.raiseError(new RuntimeException("DB load failed") with NoStackTrace)
        def loadAllGames(): IO[Map[MatchId, (GameType, Game[_, _, _, _, _])]] =
          IO.pure(Map(roomId -> (GameType.TicTacToe, game)))
      }

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(roomId, roomId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(roomId, GameOperation.GetState, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("Failed to retrieve game state")
    }

    "handle trying to mark a nonexistent game as completed" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonexistentRoomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // game actor sends a GameCompleted message to the GameManager
      gm ! GameManager.GameCompleted(nonexistentRoomId, nonexistentRoomId, GameLifecycleStatus.Completed)

      // no-op: behavior remains the same and GameManager can continue receiving messages
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.LobbiesListed]
      response.lobbies.size shouldBe 0
    }

    "allow a player to leave a lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // Alice creates the lobby
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Bob leaves
      gm ! GameManager.LeaveLobby(roomId, bob, responseProbe.ref)
      val bobLeft = responseProbe.expectMessageType[GameManager.LobbyLeft]
      bobLeft.message should include("left lobby")

      // Bob tries to leave again
      gm ! GameManager.LeaveLobby(roomId, bob, responseProbe.ref)
      val bobLeft2 = responseProbe.expectMessageType[GameManager.LobbyLeft]
      bobLeft2.message should include("already absent")

      // Alice leaves
      gm ! GameManager.LeaveLobby(roomId, alice, responseProbe.ref)
      val aliceLeft = responseProbe.expectMessageType[GameManager.LobbyLeft]
      aliceLeft.message should include("host left")
    }

    "forfeit an in-progress game when a player leaves" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Bob (O) leaves the in-progress game — Alice (X) wins by forfeit
      gm ! GameManager.LeaveLobby(roomId, bob, responseProbe.ref)
      val forfeited = responseProbe.expectMessageType[GameManager.GameForfeited]
      forfeited.state.asInstanceOf[GridGameState].winner shouldBe Some("X")

      // the room has moved to Finished (post-game) via the GameCompleted -> MatchEnded flow
      eventually {
        gm ! GameManager.GetLobbyInfo(roomId, responseProbe.ref)
        responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.Finished
      }
    }

    "reject a forfeit from a non-participant of an in-progress game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val carol = Player("carol")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Carol is not seated in the game; her leave is rejected and the game stays live
      gm ! GameManager.LeaveLobby(roomId, carol, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.MoveRejected]

      gm ! GameManager.GetLobbyInfo(roomId, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.InProgress
    }

    "handle a player trying to leave a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentRoomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.LeaveLobby(nonexistentRoomId, alice, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentRoomId)
    }

    "forward a valid move to a game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val validMove = MovePayload.TicTacToeMove(0, 0)
      gm ! GameManager.RunGameOperation(roomId, GameOperation.MakeMove(alice.id, validMove), responseProbe.ref)

      val updatedState = responseProbe.expectMessageType[GameManager.GameStatus]

      val view = updatedState.state.asInstanceOf[GridGameState]
      view.board(0)(0) shouldBe "X"
      view.currentPlayer shouldBe "O"
    }

    "return an error when forwarding an invalid move to a game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val invalidMove = MovePayload.TicTacToeMove(99, 99)
      gm ! GameManager.RunGameOperation(roomId, GameOperation.MakeMove(alice.id, invalidMove), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.MoveRejected]
      error.message should include("out of bounds")
    }

    "register a player and return its actor ref" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val replyProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, replyProbe.ref)

      val ref = replyProbe.expectMessageType[ActorRef[PlayerActor.Command]]
      ref should not be null
    }

    "remove a player on PlayerDisconnected without crashing" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val registerProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, registerProbe.ref)
      val playerRef = registerProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.PlayerDisconnected(alice.id, playerRef)

      // GM is still responsive after disconnect
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
    }

    "auto-subscribe a connected player to lobby events when they create a lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Alice connects via WebSocket
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Alice creates a lobby
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Auto-subscribe fires: alice's PlayerActor receives SendEvent(LobbyUpdated) and forwards it as a SessionEvent
      wsProbe.expectMessageType[PlayerActor.SessionEvent]

      // Bob joins — alice should receive another push event as she is subscribed
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[PlayerActor.SessionEvent]
    }

    "auto-subscribe a connected player to lobby events when they join a lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Alice creates a lobby without a WebSocket connection — no auto-subscribe for alice
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Bob connects via WebSocket, then joins
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(bob, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Auto-subscribe fires: bob's PlayerActor receives SendEvent(LobbyUpdated) and forwards it as a SessionEvent
      wsProbe.expectMessageType[PlayerActor.SessionEvent]
    }

    "subscribe a connected spectator to lobby events via SubscribePlayerToLobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val spectator = Player("spectator")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Spectator connects via WebSocket
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(spectator, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Alice creates a lobby
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Spectator subscribes to lobby events
      gm ! GameManager.SubscribePlayerToLobby(roomId, spectator.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(roomId))

      // Initial lobby state push arrives on subscribe; bob joining triggers another
      wsProbe.expectMessageType[PlayerActor.SessionEvent]
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[PlayerActor.SessionEvent]
    }

    "return an error when SubscribePlayerToLobby is called for a disconnected player" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Alice has no WebSocket connection
      gm ! GameManager.SubscribePlayerToLobby(roomId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("not connected")
    }

    "return GameAlreadyStarted when SubscribePlayerToLobby is called after the game has started" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(roomId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe a[LobbyError.GameAlreadyStarted]
    }

    "subscribe a connected spectator to game events via SubscribePlayerToGame" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val spectator = Player("spectator")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Spectator connects via WebSocket
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(spectator, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Start a game
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Spectator subscribes to game events
      gm ! GameManager.SubscribePlayerToGame(roomId, spectator.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(roomId))

      // A move is made — spectator should receive a push event
      gm ! GameManager.RunGameOperation(
        roomId,
        GameOperation.MakeMove(alice.id, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]
      wsProbe.expectMessageType[PlayerActor.SessionEvent]
    }

    "return an error when SubscribePlayerToGame is called for a disconnected player" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Alice has no WebSocket connection
      gm ! GameManager.SubscribePlayerToGame(roomId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("not connected")
    }

    "return an error when SubscribePlayerToGame targets a nonexistent active game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val spectator = Player("spectator")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Spectator is connected
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(spectator, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.SubscribePlayerToGame(UUID.randomUUID(), spectator.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No active game found")
    }

    "stop delivering game events to a spectator after UnsubscribePlayerFromGame" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // alice connects and subscribes to the in-progress game
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToGame(roomId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(roomId))
      wsProbe.expectMessageType[PlayerActor.SessionEvent] // initial game state on subscribe

      // a move while subscribed is delivered to alice's session
      gm ! GameManager.RunGameOperation(
        roomId,
        GameOperation.MakeMove(alice.id, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]
      wsProbe.expectMessageType[PlayerActor.SessionEvent] // GameStateUpdated

      // after unsubscribing, a subsequent move is no longer delivered to alice
      gm ! GameManager.UnsubscribePlayerFromGame(roomId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.UnsubscribeAcknowledged(roomId))

      gm ! GameManager.RunGameOperation(
        roomId,
        GameOperation.MakeMove(bob.id, MovePayload.TicTacToeMove(1, 1)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]
      wsProbe.expectNoMessage()
    }

    "acknowledge UnsubscribePlayerFromGame idempotently when there is no active subscription" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // unknown game and a player that was never connected: still acknowledged, no crash
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.UnsubscribePlayerFromGame(UUID.randomUUID(), UUID.randomUUID(), responseProbe.ref)
      responseProbe.expectMessageType[GameManager.UnsubscribeAcknowledged]

      // GM is still responsive
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
    }

    "push initial lobby state to WebSocket on SubscribePlayerToLobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(roomId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(roomId))

      // subscriber receives initial lobby state immediately via WebSocket
      wsProbe.expectMessageType[PlayerActor.SessionEvent]
    }

    "broadcast a chat message to an active game's subscribers and emit a ChatSent analytics event on SendChat" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val host = Player("alice")
      val guest = Player("bob")
      val published = new java.util.concurrent.ConcurrentLinkedQueue[GameEvent]()
      val recordingPublisher = new EventPublisher {
        def publish(event: GameEvent): Unit = { published.add(event); () }
      }
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, publisher = recordingPublisher))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, host, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId, guest, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // connect the host and subscribe their session to the in-progress game as a spectator
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(host, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(roomId))
      wsProbe.expectMessageType[PlayerActor.SessionEvent] // initial game state on subscribe

      gm ! GameManager.SendChat(roomId, host, "gg")

      val chat = wsProbe.expectMessageType[PlayerActor.SessionEvent].event.asInstanceOf[PlayerEvent.ChatMessage]
      chat.senderId shouldBe host.id
      chat.text shouldBe "gg"

      // the send is also recorded for analytics, tagged with the in-progress game's type
      wsProbe.awaitAssert {
        val chatEvents = published.iterator().asScala.collect { case c: GameEvent.ChatSent => c }.toList
        chatEvents.map(_.roomId) shouldBe List(roomId)
        chatEvents.head.gameType shouldBe Some(GameType.TicTacToe)
      }
    }

    "broadcast a chat message to lobby subscribers on SendChat before the game starts" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val alice = Player("alice")
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      // connect alice so CreateLobby auto-subscribes her to the new lobby
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      wsProbe.expectMessageType[PlayerActor.SessionEvent] // initial lobby state from the auto-subscribe

      gm ! GameManager.SendChat(roomId, alice, "hello")
      val chat = wsProbe.expectMessageType[PlayerActor.SessionEvent].event.asInstanceOf[PlayerEvent.ChatMessage]
      chat.text shouldBe "hello"
    }

    "persist each chat message to the chat repository on SendChat" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val chatRepo = new InMemChatRepo()
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, chatRepo = chatRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val roomId: RoomId = UUID.randomUUID()
      val sender = Player("alice")

      gm ! GameManager.SendChat(roomId, sender, "hello all")

      // append is fire-and-forget, so poll the history until the message lands
      responseProbe.awaitAssert {
        gm ! GameManager.GetChatHistory(roomId, responseProbe.ref)
        val history = responseProbe.expectMessageType[GameManager.ChatHistory]
        history.messages.map(_.text) shouldBe List("hello all")
      }
    }

    "return the recorded chat history on GetChatHistory" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val roomId: RoomId = UUID.randomUUID()
      val seeded = List(
        PlayerEvent.ChatMessage(roomId, alice, "alice", "hi", Instant.EPOCH),
        PlayerEvent.ChatMessage(roomId, bob, "bob", "hey", Instant.EPOCH)
      )
      val chatRepo = new InMemChatRepo(Map(roomId -> seeded))
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, chatRepo = chatRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetChatHistory(roomId, responseProbe.ref)
      val history = responseProbe.expectMessageType[GameManager.ChatHistory]
      history.roomId shouldBe roomId
      history.messages shouldBe seeded
    }

    "reply with an error when the chat history load fails" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val chatRepo = new InMemChatRepo(loadFails = true)
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, chatRepo = chatRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetChatHistory(UUID.randomUUID(), responseProbe.ref)
      responseProbe.expectMessageType[GameManager.ErrorResponse]
    }

    "deliver GameStateUpdated to a lobby subscriber via WebSocket after the game starts" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Alice connects via WebSocket and subscribes before the game starts
      val wsProbe = TestProbe[PlayerActor.SessionOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(roomId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(roomId))
      wsProbe.expectMessageType[PlayerActor.SessionEvent] // initial LobbyUpdated snapshot

      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[PlayerActor.SessionEvent] // LobbyUpdated on join

      // Start the game — LobbyManager passes Alice's PlayerActor ref in SpawnGame
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Make a move; Alice's WebSocket should receive GameStateUpdated from the game actor
      gm ! GameManager.RunGameOperation(
        roomId,
        GameOperation.MakeMove(alice.id, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]

      wsProbe.expectMessageType[PlayerActor.SessionEvent] // GameStateUpdated
    }

    "return an error when forwarding to a nonexistent game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentRoomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      val move = MovePayload.TicTacToeMove(0, 0)
      gm ! GameManager.RunGameOperation(nonexistentRoomId, GameOperation.MakeMove(alice.id, move), responseProbe.ref)

      responseProbe.expectMessageType[GameManager.GameNotFound].roomId shouldBe nonexistentRoomId
    }

    "return an ErrorResponse when the move payload does not match the game type" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(roomId, host) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(roomId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(roomId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // a ConnectFour move sent to a TicTacToe game can't be converted by the module, so RunGameOperation replies
      // with an ErrorResponse rather than forwarding anything to the game actor
      gm ! GameManager.RunGameOperation(
        roomId,
        GameOperation.MakeMove(host.id, MovePayload.ConnectFourMove(0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.ErrorResponse].message should include("Unsupported move type")
    }

    "return an error when SpawnGame is sent with the wrong number of players" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val roomId: RoomId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.SpawnGame(roomId, UUID.randomUUID(), GameType.TicTacToe, Seq(alice), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("players required")
    }

  }
}
