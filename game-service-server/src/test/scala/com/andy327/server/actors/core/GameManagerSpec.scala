package com.andy327.server.actors.core

import java.util.UUID

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.core.{PlayerActor, PlayerEvent}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.TicTacToeState.TicTacToeView
import com.andy327.server.http.json.{GameStateConverters, TicTacToeState}
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, Player}

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

class GameManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  implicit val runtime: IORuntime = IORuntime.global

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

      val _ = spawn(GameManager(persistProbe.ref, failingRepo, Some(readyProbe.ref)))
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

      val gm = spawn(GameManager(persistProbe.ref, slowRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val gameResponseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, gameResponseProbe.ref)
      val response = gameResponseProbe.expectMessageType[GameManager.GameResponse]
      response shouldBe GameManager.GameStatus(GameStateConverters.serializeGame(restoredGame))
    }

    "ignore RestoreGames messages in running state" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Now send an unexpected RestoreGames message while in `running` state
      gm ! GameManager.RestoreGames(Map.empty)

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

    "mark game as completed when receiving GameCompleted message" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

    "serve game state from DB after the game actor is stopped on completion" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.GameStatus]
      response.state shouldBe GameStateConverters.serializeGame(game)
    }

    "return an error when forwarding a move to a completed game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId: GameId = UUID.randomUUID()
      val playerX: PlayerId = UUID.randomUUID()
      val playerO: PlayerId = UUID.randomUUID()
      val game = TicTacToe.empty(playerX, playerO)
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, game)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(
        gameId,
        GameOperation.MakeMove(playerX, MovePayload.TicTacToeMove(0, 0)),
        responseProbe.ref
      )
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("Game has already ended")
    }

    "return an error when DB has no record for a completed game" in {
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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(5.seconds, GameManager.Ready)

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.RunGameOperation(gameId, GameOperation.GetState, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("Game state not found in database")
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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val view = updatedState.state.asInstanceOf[TicTacToeState]
      view.board(0)(0) shouldBe "X"
      view.currentPlayer shouldBe "O"
    }

    "return an error when forwarding an invalid move to a game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("out of bounds")
    }

    "register a player and return its actor ref" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val wsProbe = TestProbe[Message]()
      val replyProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, replyProbe.ref)

      val ref = replyProbe.expectMessageType[ActorRef[PlayerActor.Command]]
      ref should not be null
    }

    "remove a player on PlayerDisconnected without crashing" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val wsProbe = TestProbe[Message]()
      val registerProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, registerProbe.ref)
      registerProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.PlayerDisconnected(alice.id)

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
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Alice connects via WebSocket
      val wsProbe = TestProbe[Message]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      // Alice creates a lobby
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Auto-subscribe fires: alice's PlayerActor receives SendEvent(LobbyUpdated) and forwards it as a TextMessage
      wsProbe.expectMessageType[TextMessage]

      // Bob joins — alice should receive another push event as she is subscribed
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[TextMessage]
    }

    "auto-subscribe a connected player to lobby events when they join a lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Alice creates a lobby without a WebSocket connection — no auto-subscribe for alice
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Bob connects via WebSocket, then joins
      val wsProbe = TestProbe[Message]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(bob, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Auto-subscribe fires: bob's PlayerActor receives SendEvent(LobbyUpdated) and forwards it as a TextMessage
      wsProbe.expectMessageType[TextMessage]
    }

    "subscribe a connected spectator to lobby events via SubscribePlayerToLobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val spectator = Player("spectator")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Spectator connects via WebSocket
      val wsProbe = TestProbe[Message]()
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
      wsProbe.expectMessageType[TextMessage]
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[TextMessage]
    }

    "return an error when SubscribePlayerToLobby is called for a disconnected player" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val wsProbe = TestProbe[Message]()
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
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Spectator connects via WebSocket
      val wsProbe = TestProbe[Message]()
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
      wsProbe.expectMessageType[TextMessage]
    }

    "return an error when SubscribePlayerToGame is called for a disconnected player" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

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
      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Spectator is connected
      val wsProbe = TestProbe[Message]()
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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, _) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      val wsProbe = TestProbe[Message]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(gameId))

      // subscriber receives initial lobby state immediately via WebSocket
      wsProbe.expectMessageType[TextMessage]
    }

    "forward SubscribeToGame so the subscriber receives events after a move" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, host.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      val subscriberProbe = TestProbe[PlayerActor.Command]()
      gm ! GameManager.SubscribeToGame(gameId, subscriberProbe.ref)

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.LobbyCreated(gameId, host) = responseProbe.expectMessageType[GameManager.LobbyCreated]

      // Alice connects via WebSocket and subscribes before the game starts
      val wsProbe = TestProbe[Message]()
      val playerRefProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      gm ! GameManager.RegisterPlayer(alice, wsProbe.ref, playerRefProbe.ref)
      playerRefProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      gm ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
      responseProbe.expectMessage(GameManager.SubscribeAcknowledged(gameId))
      wsProbe.expectMessageType[TextMessage] // initial LobbyUpdated snapshot

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      wsProbe.expectMessageType[TextMessage] // LobbyUpdated on join

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

      wsProbe.expectMessageType[TextMessage] // GameStateUpdated
    }

    "handle SubscribeToGame for an unknown game without crashing" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val nonexistentId: GameId = java.util.UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val subscriberProbe = TestProbe[PlayerActor.Command]()
      gm ! GameManager.SubscribeToGame(nonexistentId, subscriberProbe.ref)

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

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      val move = MovePayload.TicTacToeMove(0, 0)
      gm ! GameManager.RunGameOperation(nonexistentGameId, GameOperation.MakeMove(alice.id, move), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No game found with gameId")
    }

    "return an error when SpawnGame is sent with the wrong number of players" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val gameId: GameId = UUID.randomUUID()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.SpawnGame(gameId, GameType.TicTacToe, Set(alice), responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("players required")
    }

  }
}
