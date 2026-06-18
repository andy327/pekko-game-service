package com.andy327.server.actors.core

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime

import fs2.Stream
import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.{GameRepository, MoveHistoryRepository, MoveRecord}
import com.andy327.server.actors.core.{PlayerActor, PlayerEvent}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.chat.ChatRepository
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.{GameStateConverters, GridGameState}
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, LobbyRepository, Player}
import com.andy327.server.pubsub.{GameEventPublisher, GameEventSubscriber}

/** In-memory GameRepository for unit tests */
class InMemRepo(initialGames: Map[GameId, (GameType, Game[_, _, _, _, _])] = Map.empty) extends GameRepository {
  private val db = scala.collection.concurrent.TrieMap(initialGames.toSeq: _*)

  def initialize(): IO[Unit] = IO.unit

  def saveGame(id: GameId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] =
    IO(db.update(id, (tpe, g)))

  def loadGame(id: GameId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] =
    IO(db.get(id).collect { case (`tpe`, g) => g })

  def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] =
    IO(db.toMap)
}

/** In-memory MoveHistoryRepository for unit tests; `loadFails` forces loadMoves to raise. */
class InMemMoveRepo(initial: Map[GameId, List[MoveRecord]] = Map.empty, loadFails: Boolean = false)
    extends MoveHistoryRepository {
  private val db = scala.collection.concurrent.TrieMap(initial.toSeq: _*)

  def initialize(): IO[Unit] = IO.unit

  def appendMove(gameId: GameId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] =
    IO(db.update(gameId, db.getOrElse(gameId, Nil) :+ MoveRecord(seq, playerId, move, Instant.EPOCH)))

  def loadMoves(gameId: GameId): IO[List[MoveRecord]] =
    if (loadFails) IO.raiseError(new RuntimeException("move load failure") with NoStackTrace)
    else IO.pure(db.getOrElse(gameId, Nil))
}

/** In-memory ChatRepository for unit tests; records appends and `loadFails` forces recent to raise. */
class InMemChatRepo(initial: Map[GameId, List[PlayerEvent.ChatMessage]] = Map.empty, loadFails: Boolean = false)
    extends ChatRepository {
  private val db = scala.collection.concurrent.TrieMap(initial.toSeq: _*)

  def append(message: PlayerEvent.ChatMessage): IO[Unit] =
    IO(db.update(message.gameId, db.getOrElse(message.gameId, Nil) :+ message))

  def recent(gameId: GameId): IO[List[PlayerEvent.ChatMessage]] =
    if (loadFails) IO.raiseError(new RuntimeException("chat load failure") with NoStackTrace)
    else IO.pure(db.getOrElse(gameId, Nil))
}

class GameManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  implicit val runtime: IORuntime = IORuntime.global

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(gameId: GameId): IO[Unit] = IO.unit
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
        def saveGame(id: GameId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: GameId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] =
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
        def saveGame(id: GameId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: GameId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] = IO.sleep(1.second) *> IO.pure(Map.empty)
      }
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, slowRepo, noOpLobbyRepo))

      // Send a command that would be stashed during initialization
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)

      // Initially, no response because it's still initializing
      responseProbe.expectNoMessage(500.millis)

      // Eventually, after restore, the stashed message is processed
      val response = responseProbe.expectMessageType[GameManager.LobbyCreated](2.seconds)
      response.gameId.toString should not be empty
    }

    "restore saved games from the game repository on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameId: GameId = UUID.randomUUID()
      val restoredGame = TicTacToe.empty(alice, bob)
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, restoredGame)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val gameResponseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, gameResponseProbe.ref)
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
      response.gameId.toString should not be empty

      gm ! GameManager.GetLobbyInfo(response.gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]

      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "return an error when retrieving metadata for a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonexistentGameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetLobbyInfo(nonexistentGameId, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentGameId)
    }

    "allow a second player to join a lobby and change its status to ReadyToStart" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
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
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      gm ! GameManager.JoinLobby(gameId, alice, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.AlreadyInLobby(gameId)

      gm ! GameManager.GetLobbyInfo(gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]

      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "prevent a player from joining a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentGameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.JoinLobby(nonexistentGameId, alice, responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentGameId)
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
      val GameManager.LobbyCreated(gameId, _) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Carl tries to join - should be rejected
      gm ! GameManager.JoinLobby(gameId, carl, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyFull(gameId)
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
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Game bgeins
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Carl tries to join - should be rejected
      gm ! GameManager.JoinLobby(gameId, carl, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotJoinable(gameId)
    }

    "allow a host to start a game from a ready lobby and persist the snapshot" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.GameStarted(gameId))

      val snapshot = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      snapshot.gameId shouldBe gameId
    }

    "prevent a non-host player from starting the game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      val GameManager.LobbyJoined(_, _, joinedPlayer) = responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Bob tries to start the game
      gm ! GameManager.StartGame(gameId, joinedPlayer.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.NotHostError(gameId)
    }

    "prevent a player from starting a nonexistent game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentGameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.StartGame(nonexistentGameId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentGameId)
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
      val GameManager.LobbyCreated(gameId1, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameId1, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId1, alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Game 2: ReadyToStart
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId2, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameId2, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Game 2: WaitingForPlayers
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId3, _) = responseProbe.receiveMessage()

      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.LobbiesListed]
      response.lobbies.map(_.gameId) should contain only (gameId2, gameId3)
    }

    "return the recorded move history for a game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameId: GameId = UUID.randomUUID()
      val moves = List(
        MoveRecord(0, alice, Json.obj("col" -> 3.asJson), Instant.EPOCH),
        MoveRecord(1, bob, Json.obj("col" -> 4.asJson), Instant.EPOCH)
      )
      val moveRepo = new InMemMoveRepo(Map(gameId -> moves))
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, moveRepo = moveRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.GetMoveHistory(gameId, responseProbe.ref)

      responseProbe.expectMessage(GameManager.MoveHistory(gameId, moves))
    }

    "return an empty move history for a game with no recorded moves" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameId: GameId = UUID.randomUUID()
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, moveRepo = new InMemMoveRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.GetMoveHistory(gameId, responseProbe.ref)

      responseProbe.expectMessage(GameManager.MoveHistory(gameId, Nil))
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

    "mark game as completed when receiving GameCompleted message" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // game actor sends a GameCompleted message to the GameManager
      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.GetLobbyInfo(gameId, responseProbe.ref)
      val GameManager.LobbyInfo(metadata) = responseProbe.expectMessageType[GameManager.LobbyInfo]
      metadata.status shouldBe GameLifecycleStatus.Completed
    }

    "mark a restored lobby as completed when its restored game finishes" in {
      // Simulates a restart mid-game: the game comes back from the game repository and its
      // InProgress lobby comes back from the lobby repository. When the game later completes,
      // the lobby must still be marked completed and deleted from persistent storage.
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val host = Player("alice")
      val guest = Player("bob")
      val game = TicTacToe.empty(host.id, guest.id)
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, game)))

      val restoredLobby = LobbyMetadata(
        gameId,
        GameType.TicTacToe,
        Map(host.id -> host, guest.id -> guest),
        host.id,
        GameLifecycleStatus.InProgress
      )
      val deletedLobbies = scala.collection.concurrent.TrieMap.empty[GameId, Unit]
      val restoringLobbyRepo: LobbyRepository = new LobbyRepository {
        override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
        override def deleteLobby(gameId: GameId): IO[Unit] = IO(deletedLobbies.update(gameId, ()))
        override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(List(restoredLobby))
      }

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, restoringLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // sanity check: the restored lobby is known and InProgress
      gm ! GameManager.GetLobbyInfo(gameId, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.InProgress

      // the restored game completes
      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.GetLobbyInfo(gameId, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyInfo].metadata.status shouldBe GameLifecycleStatus.Completed

      // the fire-and-forget Redis delete is eventually dispatched
      responseProbe.awaitAssert(deletedLobbies.keySet should contain(gameId))
    }

    "serve game state from DB after the game actor is stopped on completion" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.GameStatus]
      response.state shouldBe GameStateConverters.serializeGame(game, None)
    }

    "return an error when forwarding a move to a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(
        gameId,
        GameOperation.MakeMove(playerX, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      val error = responseProbe.expectMessageType[GameManager.MoveRejected]
      error.message should include("Game has already ended")
    }

    "return GameNotFound when DB has no record for a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: GameId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: GameId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] =
          IO.pure(Map(gameId -> (GameType.TicTacToe, game)))
      }

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameNotFound].gameId shouldBe gameId
    }

    "return an error when the DB call fails for a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: GameId, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: GameId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] =
          IO.raiseError(new RuntimeException("DB load failed") with NoStackTrace)
        def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] =
          IO.pure(Map(gameId -> (GameType.TicTacToe, game)))
      }

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, onReady = Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("Failed to retrieve game state")
    }

    "handle trying to mark a nonexistent game as completed" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonexistentGameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // game actor sends a GameCompleted message to the GameManager
      gm ! GameManager.GameCompleted(nonexistentGameId, GameLifecycleStatus.Completed)

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
      val GameManager.LobbyCreated(gameId, _) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Bob leaves
      gm ! GameManager.LeaveLobby(gameId, bob, responseProbe.ref)
      val bobLeft = responseProbe.expectMessageType[GameManager.LobbyLeft]
      bobLeft.message should include("left lobby")

      // Bob tries to leave again
      gm ! GameManager.LeaveLobby(gameId, bob, responseProbe.ref)
      val bobLeft2 = responseProbe.expectMessageType[GameManager.LobbyLeft]
      bobLeft2.message should include("already absent")

      // Alice leaves
      gm ! GameManager.LeaveLobby(gameId, alice, responseProbe.ref)
      val aliceLeft = responseProbe.expectMessageType[GameManager.LobbyLeft]
      aliceLeft.message should include("host left")
    }

    "handle a player trying to leave a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentGameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.LeaveLobby(nonexistentGameId, alice, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.LobbyErrorResponse]
      error.error shouldBe LobbyError.LobbyNotFound(nonexistentGameId)
    }

    "forward a valid move to a game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val validMove = MovePayload.TicTacToeMove(0, 0)
      gm ! GameManager.RunGameOperation(gameId, GameOperation.MakeMove(alice.id, validMove), responseProbe.ref)

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
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val invalidMove = MovePayload.TicTacToeMove(99, 99)
      gm ! GameManager.RunGameOperation(gameId, GameOperation.MakeMove(alice.id, invalidMove), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.MoveRejected]
      error.message should include("out of bounds")
    }

    "register a player and return its actor ref" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val wsProbe = TestProbe[PlayerActor.WsOutput]()
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

      val wsProbe = TestProbe[PlayerActor.WsOutput]()
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
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Alice creates a lobby
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Auto-subscribe fires: alice's PlayerActor receives SendEvent(LobbyUpdated) and forwards it as a TextMessage
      wsProbe.expectMessageType[PlayerActor.WsMessage]

      // Bob joins — alice should receive another push event as she is subscribed
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[PlayerActor.WsMessage]
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
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Bob connects via WebSocket, then joins
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(bob, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Auto-subscribe fires: bob's PlayerActor receives SendEvent(LobbyUpdated) and forwards it as a TextMessage
      wsProbe.expectMessageType[PlayerActor.WsMessage]
    }

    "subscribe a connected spectator to lobby events via SubscribePlayerToLobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val spectator = Player("spectator")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Spectator connects via WebSocket
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(spectator, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Alice creates a lobby
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Spectator subscribes to lobby events
      gm ! GameManager.SubscribePlayerToLobby(gameId, spectator.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(gameId))

      // Initial lobby state push arrives on subscribe; bob joining triggers another
      wsProbe.expectMessageType[PlayerActor.WsMessage]
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[PlayerActor.WsMessage]
    }

    "return an error when SubscribePlayerToLobby is called for a disconnected player" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Alice has no WebSocket connection
      gm ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
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
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
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
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(spectator, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Start a game
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Spectator subscribes to game events
      gm ! GameManager.SubscribePlayerToGame(gameId, spectator.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(gameId))

      // A move is made — spectator should receive a push event
      gm ! GameManager.RunGameOperation(
        gameId,
        GameOperation.MakeMove(alice.id, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]
      wsProbe.expectMessageType[PlayerActor.WsMessage]
    }

    "return an error when SubscribePlayerToGame is called for a disconnected player" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Alice has no WebSocket connection
      gm ! GameManager.SubscribePlayerToGame(gameId, alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("not connected")
    }

    "return an error when SubscribePlayerToGame targets a nonexistent active game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val spectator = Player("spectator")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      // Spectator is connected
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(spectator, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.SubscribePlayerToGame(UUID.randomUUID(), spectator.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No active game found")
    }

    "push initial lobby state to WebSocket on SubscribePlayerToLobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(gameId))

      // subscriber receives initial lobby state immediately via WebSocket
      wsProbe.expectMessageType[PlayerActor.WsMessage]
    }

    "broadcast a chat message to an active game's subscribers and publish it on SendChat" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val host = Player("alice")
      val guest = Player("bob")
      val published = new java.util.concurrent.ConcurrentLinkedQueue[(GameId, PlayerEvent)]()
      val recordingPublisher = new GameEventPublisher {
        def publish(gameId: GameId, event: PlayerEvent): Unit = { published.add((gameId, event)); () }
      }
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, publisher = recordingPublisher))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, host, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameId, guest, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val subscriberProbe = createTestProbe[PlayerActor.Command]()
      gm ! GameManager.SubscribeToGame(gameId, host.id, subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial game state on subscribe

      gm ! GameManager.SendChat(gameId, host, "gg")

      val Some(chat) = subscriberProbe.expectMessageType[PlayerActor.SendEvent].event match {
        case c: PlayerEvent.ChatMessage => Some(c)
        case _                          => None
      }
      chat.gameId shouldBe gameId
      chat.senderId shouldBe host.id
      chat.senderName shouldBe "alice"
      chat.text shouldBe "gg"

      // also relayed to other instances via the publisher
      subscriberProbe.awaitAssert(published.size shouldBe 1)
      published.peek()._2 shouldBe a[PlayerEvent.ChatMessage]
    }

    "broadcast a chat message to lobby subscribers on SendChat before the game starts" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val alice = Player("alice")
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      // connect alice so CreateLobby auto-subscribes her to the new lobby
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      wsProbe.expectMessageType[PlayerActor.WsMessage] // initial lobby state from the auto-subscribe

      gm ! GameManager.SendChat(gameId, alice, "hello")
      val frame = wsProbe.expectMessageType[PlayerActor.WsMessage].message.asInstanceOf[TextMessage.Strict].text
      frame should include("ChatMessage")
      frame should include("hello")
    }

    "persist each chat message to the chat repository on SendChat" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val chatRepo = new InMemChatRepo()
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, chatRepo = chatRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val gameId: GameId = UUID.randomUUID()
      val sender = Player("alice")

      gm ! GameManager.SendChat(gameId, sender, "hello all")

      // append is fire-and-forget, so poll the history until the message lands
      responseProbe.awaitAssert {
        gm ! GameManager.GetChatHistory(gameId, responseProbe.ref)
        val history = responseProbe.expectMessageType[GameManager.ChatHistory]
        history.messages.map(_.text) shouldBe List("hello all")
      }
    }

    "return the recorded chat history on GetChatHistory" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameId: GameId = UUID.randomUUID()
      val seeded = List(
        PlayerEvent.ChatMessage(gameId, alice, "alice", "hi", Instant.EPOCH),
        PlayerEvent.ChatMessage(gameId, bob, "bob", "hey", Instant.EPOCH)
      )
      val chatRepo = new InMemChatRepo(Map(gameId -> seeded))
      val gm = spawn(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, chatRepo = chatRepo))
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetChatHistory(gameId, responseProbe.ref)
      val history = responseProbe.expectMessageType[GameManager.ChatHistory]
      history.gameId shouldBe gameId
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

    "forward SubscribeToGame so the subscriber receives events after a move" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val subscriberProbe = TestProbe[PlayerActor.Command]()
      gm ! GameManager.SubscribeToGame(gameId, alice.id, subscriberProbe.ref)

      gm ! GameManager.RunGameOperation(
        gameId,
        GameOperation.MakeMove(alice.id, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]

      val event = subscriberProbe.expectMessageType[PlayerActor.SendEvent]
      event.event shouldBe a[PlayerEvent.GameStateUpdated]
    }

    "deliver GameStateUpdated to a lobby subscriber via WebSocket after the game starts" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Alice connects via WebSocket and subscribes before the game starts
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(gameId))
      wsProbe.expectMessageType[PlayerActor.WsMessage] // initial LobbyUpdated snapshot

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[PlayerActor.WsMessage] // LobbyUpdated on join

      // Start the game — LobbyManager passes Alice's PlayerActor ref in SpawnGame
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Make a move; Alice's WebSocket should receive GameStateUpdated from the game actor
      gm ! GameManager.RunGameOperation(
        gameId,
        GameOperation.MakeMove(alice.id, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStatus]

      wsProbe.expectMessageType[PlayerActor.WsMessage] // GameStateUpdated
    }

    "handle SubscribeToGame for an unknown game without crashing" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonexistentId: GameId = java.util.UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val subscriberProbe = TestProbe[PlayerActor.Command]()
      gm ! GameManager.SubscribeToGame(nonexistentId, UUID.randomUUID(), subscriberProbe.ref)

      // GM is still responsive
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
    }

    "return an error when forwarding to a nonexistent game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val nonexistentGameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      val move = MovePayload.TicTacToeMove(0, 0)
      gm ! GameManager.RunGameOperation(nonexistentGameId, GameOperation.MakeMove(alice.id, move), responseProbe.ref)

      responseProbe.expectMessageType[GameManager.GameNotFound].gameId shouldBe nonexistentGameId
    }

    "return an error when SpawnGame is sent with the wrong number of players" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val gameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.SpawnGame(gameId, GameType.TicTacToe, Set(alice), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("players required")
    }

    "register a remote player with the subscriber and route events on SubscribeToGame for a non-local game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonLocalGameId: GameId = UUID.randomUUID()
      val json = """{"type":"GameStateUpdated"}"""

      val queue = Queue.unbounded[IO, (String, String)].unsafeRunSync()
      val subscriber = GameEventSubscriber.create(Stream.fromQueueUnterminated(queue)).unsafeRunSync()
      val fiber = subscriber.run.start.unsafeRunSync()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, subscriber = Some(subscriber)))

      // Register a player via GameManager so the ref lives inside the actor system
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(Player("alice"), wsProbe.ref, playerRefProbe.ref)
      val playerRef = playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Ask GM to subscribe the player to a non-local game
      gm ! GameManager.SubscribeToGame(nonLocalGameId, UUID.randomUUID(), playerRef)

      // Drain in-flight messages so SubscribeToGame is processed before we enqueue
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
      Thread.sleep(50) // let unsafeRunAndForget(registerPlayer) complete

      // Push an event through the subscriber's stream
      queue.offer((s"game-events:$nonLocalGameId", json)).unsafeRunSync()
      wsProbe.expectMessageType[PlayerActor.WsMessage].message.asInstanceOf[TextMessage.Strict].text shouldBe json

      fiber.cancel.unsafeRunSync()
    }

    "unregister a player from the subscriber on PlayerDisconnected" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonLocalGameId: GameId = UUID.randomUUID()
      val json = """{"type":"GameStateUpdated"}"""

      val queue = Queue.unbounded[IO, (String, String)].unsafeRunSync()
      val subscriber = GameEventSubscriber.create(Stream.fromQueueUnterminated(queue)).unsafeRunSync()
      val fiber = subscriber.run.start.unsafeRunSync()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, subscriber = Some(subscriber)))

      val player = Player("alice")
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(player, wsProbe.ref, playerRefProbe.ref)
      val playerRef = playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribeToGame(nonLocalGameId, player.id, playerRef)
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
      Thread.sleep(50) // let unsafeRunAndForget(registerPlayer) complete

      // sanity: the player is registered and receiving the remote game's events
      queue.offer((s"game-events:$nonLocalGameId", json)).unsafeRunSync()
      wsProbe.expectMessageType[PlayerActor.WsMessage]

      // disconnect drops the player from the subscriber registry (and closes its session stream)
      gm ! GameManager.PlayerDisconnected(player.id, playerRef)
      wsProbe.expectMessage(PlayerActor.WsComplete) // the PlayerActor completes its stream as it stops
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
      Thread.sleep(50) // let unsafeRunAndForget(unregisterPlayer) complete

      // a further event must NOT reach the now-unregistered player
      queue.offer((s"game-events:$nonLocalGameId", json)).unsafeRunSync()
      wsProbe.expectNoMessage(100.millis)

      fiber.cancel.unsafeRunSync()
    }

    "call unregisterGame on the subscriber when a game completes" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val json = """{"type":"GameStateUpdated"}"""

      val queue = Queue.unbounded[IO, (String, String)].unsafeRunSync()
      val subscriber = GameEventSubscriber.create(Stream.fromQueueUnterminated(queue)).unsafeRunSync()
      val fiber = subscriber.run.start.unsafeRunSync()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo, subscriber = Some(subscriber)))

      // Start a game so it appears in activeGames
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Register a player directly with the subscriber (simulating a remote instance)
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val playerRef = spawn(PlayerActor(alice, wsProbe.ref))
      subscriber.registerPlayer(gameId, playerRef).unsafeRunSync()

      // Sanity check: events are routed before completion
      queue.offer((s"game-events:$gameId", json)).unsafeRunSync()
      wsProbe.expectMessageType[PlayerActor.WsMessage]

      // Complete the game — should call unregisterGame on the subscriber
      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      // Drain in-flight messages and let unsafeRunAndForget(unregisterGame) complete
      gm ! GameManager.ListLobbies(None, 1, 20, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbiesListed]
      Thread.sleep(50)

      // Push another event — player should NOT receive it (game unregistered)
      queue.offer((s"game-events:$gameId", json)).unsafeRunSync()
      wsProbe.expectNoMessage(100.millis)

      fiber.cancel.unsafeRunSync()
    }

  }
}
