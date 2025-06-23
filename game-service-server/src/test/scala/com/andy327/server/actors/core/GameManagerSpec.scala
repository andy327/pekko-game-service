package com.andy327.server.actors.core

import scala.concurrent.duration._
import scala.util.control.NoStackTrace

import cats.effect.IO

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.{GameError, TicTacToe}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.http.json.TicTacToeState.TicTacToeView
import com.andy327.server.http.json.{GameState, GameStateConverters}
import com.andy327.server.lobby.{GameLifecycleStatus, Player}

/** In-memory GameRepository for unit tests */
class InMemRepo(initialGames: Map[String, (GameType, Game[_, _, _, _, _])] = Map.empty) extends GameRepository {
  private val db = scala.collection.concurrent.TrieMap(initialGames.toSeq: _*)

  def initialize(): IO[Unit] = IO.unit

  def saveGame(id: String, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] =
    IO(db.update(id, (tpe, g)))

  def loadGame(id: String, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] =
    IO(db.get(id).collect { case (`tpe`, g) => g })

  def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] =
    IO(db.toMap)
}

class GameManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "GameManager" should {
    "spawn a game actor and persist an empty snapshot" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob"), responseProbe.ref)

      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()
      gameId.length should be > 0

      val save = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      save.gameId shouldBe gameId
      save.gameType shouldBe GameType.TicTacToe
      save.game shouldBe TicTacToe.empty("alice", "bob")
    }

    "return an error when not specifying the correct number of players" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob", "carl"), responseProbe.ref)

      val response = responseProbe.expectMessageType[GameManager.ErrorResponse]
      response.message should include("Invalid number of players")
    }

    "return an error when forwarding to a nonexistent game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ForwardToGame("nonexistent", "noop-msg", Some(responseProbe.ref))

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No game found with gameId")
    }

    "handle database failure gracefully on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val failingRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: String, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: String, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] =
          IO.raiseError(new RuntimeException("DB failure") with NoStackTrace)
      }

      val _ = spawn(GameManager(persistProbe.ref, failingRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready)
    }

    "restore saved games from the game repository on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameId = "restored-game"
      val restoredGame = TicTacToe.empty("alice", "bob")
      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, restoredGame)))

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val gameStateProbe = TestProbe[Either[GameError, GameState]]()
      val gameResponseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.ForwardToGame(gameId, TicTacToeActor.GetState(gameStateProbe.ref), Some(gameResponseProbe.ref))
      val response = gameResponseProbe.expectMessageType[GameManager.GameResponse]
      response shouldBe GameManager.GameStatus(GameStateConverters.serializeGame(restoredGame))
    }

    "stash messages during initialization and process them after RestoreGames" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val responseProbe = TestProbe[GameManager.GameResponse]()
      val slowRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def saveGame(id: String, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: String, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] = IO.sleep(1.second) *> IO.pure(Map.empty)
      }

      val gm = spawn(GameManager(persistProbe.ref, slowRepo))

      // Send a command that would be stashed during initialization
      gm ! GameManager.CreateGame(GameType.TicTacToe, List("alice", "bob"), responseProbe.ref)

      // Initially, no response because it's still initializing
      responseProbe.expectNoMessage(500.millis)

      // Eventually, after restore, the stashed message is processed
      val response = responseProbe.expectMessageType[GameManager.GameCreated](2.seconds)
      assert(response.gameId.nonEmpty)
    }

    "ignore RestoreGames messages in running state" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      // Now send an unexpected RestoreGames message while in `running` state
      gm ! GameManager.RestoreGames(Map.empty)

      // Sanity check: send a valid command and expect the proper response
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob"), responseProbe.ref)

      val response = responseProbe.expectMessageType[GameManager.GameCreated]
      assert(response.gameId.nonEmpty)
    }

    "create a new lobby and return its metadata" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)

      val response = responseProbe.expectMessageType[GameManager.GameCreated]
      assert(response.gameId.nonEmpty)

      gm ! GameManager.GetLobbyMetadata(response.gameId, responseProbe.ref)
      val GameManager.LobbyMetadata(metadata) = responseProbe.expectMessageType[GameManager.LobbyMetadata]

      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "return an error when retrieving metadata for a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.GetLobbyMetadata("nonexistent", responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No game with gameId: nonexistent")
    }

    "allow a second player to join a lobby and change its status to ReadyToStart" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.GameCreated(gameId) = responseProbe.expectMessageType[GameManager.GameCreated]

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
      val GameManager.GameCreated(gameId) = responseProbe.expectMessageType[GameManager.GameCreated]

      gm ! GameManager.JoinLobby(gameId, alice, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("already in game")

      gm ! GameManager.GetLobbyMetadata(gameId, responseProbe.ref)
      val GameManager.LobbyMetadata(metadata) = responseProbe.expectMessageType[GameManager.LobbyMetadata]

      metadata.status shouldBe GameLifecycleStatus.WaitingForPlayers
    }

    "prevent a player from joining a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.JoinLobby("nonexistent", alice, responseProbe.ref)

      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No such lobby")
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
      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Carl tries to join - should be rejected
      gm ! GameManager.JoinLobby(gameId, carl, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("lobby is full")
    }

    "allow a host to start a game from a ready lobby and persist the snapshot" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")
      val bob = Player("bob")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, alice.id, responseProbe.ref)
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
      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Bob tries to start the game
      gm ! GameManager.StartGame(gameId, bob.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("Only host can start")
    }

    "prevent a player from starting a nonexistent game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.StartGame("nonexistent", alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No such game")
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
      val GameManager.GameCreated(gameId1) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameId1, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]
      gm ! GameManager.StartGame(gameId1, alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Game 2: ReadyToStart
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.GameCreated(gameId2) = responseProbe.receiveMessage()
      gm ! GameManager.JoinLobby(gameId2, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Game 2: WaitingForPlayers
      gm ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val GameManager.GameCreated(gameId3) = responseProbe.receiveMessage()

      gm ! GameManager.ListLobbies(responseProbe.ref)
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
      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()

      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      gm ! GameManager.StartGame(gameId, alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // game actor sends a GameCompleted message to the GameManager
      gm ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)

      gm ! GameManager.GetLobbyMetadata(gameId, responseProbe.ref)
      val GameManager.LobbyMetadata(metadata) = responseProbe.expectMessageType[GameManager.LobbyMetadata]
      metadata.status shouldBe GameLifecycleStatus.Completed
    }

    "handle trying to mark a nonexistent game as completed" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      // game actor sends a GameCompleted message to the GameManager
      gm ! GameManager.GameCompleted("nonexistent", GameLifecycleStatus.Completed)

      // no-op: behavior remains the same and GameManager can continue receiving messages
      gm ! GameManager.ListLobbies(responseProbe.ref)
      val response = responseProbe.expectMessageType[GameManager.LobbiesListed]
      response.lobbies.size shouldBe 0
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
      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Game bgeins
      gm ! GameManager.StartGame(gameId, alice.id, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.GameStarted]

      // Carl tries to join - should be rejected
      gm ! GameManager.JoinLobby(gameId, carl, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("game already started or ended")
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
      val GameManager.GameCreated(gameId) = responseProbe.receiveMessage()

      // Bob joins
      gm ! GameManager.JoinLobby(gameId, bob, responseProbe.ref)
      responseProbe.expectMessageType[GameManager.LobbyJoined]

      // Bob leaves
      gm ! GameManager.LeaveLobby(gameId, bob.id, responseProbe.ref)
      val bobLeft = responseProbe.expectMessageType[GameManager.LobbyLeft]
      bobLeft.message should include("left lobby")

      // Alice leaves
      gm ! GameManager.LeaveLobby(gameId, alice.id, responseProbe.ref)
      val aliceLeft = responseProbe.expectMessageType[GameManager.LobbyLeft]
      aliceLeft.message should include("host left")
    }

    "handle a player trying to leave a nonexistent lobby" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val alice = Player("alice")

      val gm = spawn(GameManager(persistProbe.ref, gameRepo))

      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.LeaveLobby("nonexistent", alice.id, responseProbe.ref)
      val error = responseProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No such lobby")
    }
  }
}
