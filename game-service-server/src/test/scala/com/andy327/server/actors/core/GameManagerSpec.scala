package com.andy327.server.actors.core

import scala.concurrent.duration._

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

/** In-memory GameRepository for unit tests */
class InMemRepo(initialGames: Map[String, (GameType, Game[_, _, _, _, _])] = Map.empty) extends GameRepository {
  private val db = scala.collection.concurrent.TrieMap(initialGames.toSeq: _*)

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
      val readyProbe = TestProbe[GameManager.Ready.type]()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready) // wait for initialization

      val replyProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob"), replyProbe.ref)

      val GameManager.GameCreated(gameId) = replyProbe.receiveMessage()
      gameId.length should be > 0

      val save = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      save.gameId shouldBe gameId
      save.gameType shouldBe GameType.TicTacToe
      save.game shouldBe TicTacToe.empty("alice", "bob")
    }

    "return error when not specifying the correct number of players" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val readyProbe = TestProbe[GameManager.Ready.type]()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready) // wait for initialization

      val replyProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob", "carl"), replyProbe.ref)

      val response = replyProbe.expectMessageType[GameManager.ErrorResponse]
      response.message should include("Expected 2 players")
    }

    "return error when forwarding to unknown game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready) // wait for initialization

      val replyProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ForwardToGame("no-such-id", "noop-msg", Some(replyProbe.ref))

      val error = replyProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No game found with gameId")
    }

    "handle database failure gracefully on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val failingRepo = new GameRepository {
        def saveGame(id: String, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadGame(id: String, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] =
          IO.raiseError(new RuntimeException("DB failure"))
      }

      val _ = spawn(GameManager(persistProbe.ref, failingRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready)
    }

    "restore saved games from the game repository on startup" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gameId = "restored-game"
      val restoredGame = TicTacToe.empty("alice", "bob")

      val gameRepo = new InMemRepo(Map(gameId -> (GameType.TicTacToe, restoredGame)))
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready)

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
      val readyProbe = TestProbe[GameManager.Ready.type]()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready)

      // Now send an unexpected RestoreGames message while in `running` state
      gm ! GameManager.RestoreGames(Map.empty)

      // Sanity check: send a valid command and expect the proper response
      val responseProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob"), responseProbe.ref)

      val response = responseProbe.expectMessageType[GameManager.GameCreated]
      assert(response.gameId.nonEmpty)
    }
  }
}
