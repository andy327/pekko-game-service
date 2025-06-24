package com.andy327.server.actors.tictactoe

import java.util.UUID

import scala.util.control.NoStackTrace

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, PlayerId}
import com.andy327.model.tictactoe.{GameError, Location, O, TicTacToe, X}
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameState, TicTacToeState}
import com.andy327.server.lobby.GameLifecycleStatus

class TicTacToeActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  "TicTacToeActor" should {
    "return an empty 3×3 board on GetState" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-1", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val result = replyProbe.receiveMessage()

      result match {
        case Right(TicTacToeState(board, current, winner, draw)) =>
          board.flatten should contain only "" // no moves yet
          current shouldBe "X"
          winner shouldBe None
          draw shouldBe false
        case other =>
          fail(s"Unexpected response: $other")
      }
    }

    "reject game creation with wrong number of players" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()

      val thrown = intercept[IllegalArgumentException] {
        TicTacToeActor.create("bad-game", Seq(alice), persistProbe.ref, dummyGameManager)
      }

      thrown.getMessage should include("Tic-Tac-Toe needs exactly two players")
    }

    "restore a game state from snapshot" in {
      val snapshotState = TicTacToe(
        playerX = alice,
        playerO = bob,
        board = Vector(
          Vector(Some(X), Some(O), Some(X)),
          Vector(Some(O), Some(O), Some(X)),
          Vector(None, None, Some(X))
        ),
        currentPlayer = O,
        winner = Some(X),
        isDraw = false
      )

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()

      // build behavior from snapshot and spawn it
      val behavior = TicTacToeActor.fromSnapshot("restored-game", snapshotState, persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      // ask for state and verify it matches the snapshot
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val result = replyProbe.receiveMessage()

      result match {
        case Right(TicTacToeState(board, current, winner, draw)) =>
          board(0)(0) shouldBe "X"
          board(1)(1) shouldBe "O"
          current shouldBe "O"
          winner shouldBe Some("X")
          draw shouldBe false
        case other =>
          fail(s"Unexpected response: $other")
      }
    }

    "fail to restore state from snapshot with an invalid game type" in {
      val dummyGame = new Game[Any, Any, Any, Any, Any] {
        override def play(player: Any, move: Any): Either[Any, Any] = Left("not implemented")
        override def currentState: Any = "dummy"
        override def currentPlayer: Any = "dummy"
        override def gameStatus: Any = "dummy"
      }

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()

      // build behavior from snapshot and spawn it
      val behavior = TicTacToeActor.fromSnapshot("dummy-game", dummyGame, persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      persistProbe.expectTerminated(actor)
    }

    "apply a valid move and switch currentPlayer" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-2", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Alice (X) plays (0,0)
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      val state1 = replyProbe.receiveMessage()

      state1 match {
        case Right(TicTacToeState(board, current, _, _)) =>
          board(0)(0) shouldBe "X"
          current shouldBe "O"
        case other => fail(s"Unexpected: $other")
      }

      // Bob (O) plays (1,1)
      actor ! TicTacToeActor.MakeMove(bob, Location(1, 1), replyProbe.ref)
      val state2 = replyProbe.receiveMessage()

      state2 match {
        case Right(TicTacToeState(board, current, _, _)) =>
          board(0)(0) shouldBe "X"
          board(1)(1) shouldBe "O"
          current shouldBe "X"
        case other => fail(s"Unexpected: $other")
      }
    }

    "reject a move when it is not the player's turn" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-3", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Bob tries to move first (but it's Alice's turn)
      actor ! TicTacToeActor.MakeMove(bob, Location(0, 0), replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from a player not in the game" in {
      val eve: PlayerId = UUID.randomUUID()
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-4", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.MakeMove(eve, Location(0, 0), replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe Left(GameError.InvalidPlayer(eve))
    }

    "reject an invalid move" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-5", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Alice (X) tries to play (0,3)
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 3), replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe Left(GameError.OutOfBounds)
    }

    "reject a move on a completed game" in {
      val completedGame = TicTacToe(
        playerX = alice,
        playerO = bob,
        board = Vector(
          Vector(Some(X), Some(O), Some(X)),
          Vector(Some(X), Some(X), Some(O)),
          Vector(Some(O), Some(X), Some(O))
        ),
        currentPlayer = O,
        winner = None,
        isDraw = true
      )
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val actor = spawn(TicTacToeActor.fromSnapshot("game-6", completedGame, persistProbe.ref, dummyGameManager))

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Alice (X) tries to play (0,0)
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe Left(GameError.GameOver)
    }

    "log success and continue when SnapShotSaved succeeds" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-7", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Trigger a move, which will cause a SaveSnapshot to be sent
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), replyProbe.ref)

      // Expect the SaveSnapshot command from the actor
      val saveMsg = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]

      // Simulate a successful persistence callback by sending a message to the replyTo adapter
      saveMsg.replyTo ! PersistenceProtocol.SnapshotSaved(Right(()))

      // Confirm the actor is still functioning
      val getStateProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(getStateProbe.ref)

      val state = getStateProbe.receiveMessage()
      state shouldBe a[Right[_, _]]
    }

    "log error and continue when SnapshotSaved fails" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-8", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      // Send SnapshotSaved failure message
      val ex = new RuntimeException("artificial test failure") with NoStackTrace
      actor ! TicTacToeActor.SnapshotSaved(Left(ex))

      // Confirm actor is still alive and behavior is intact
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)
      val response = replyProbe.receiveMessage()

      response shouldBe a[Right[_, _]]
    }

    "log success and continue when SnapshotLoaded succeeds" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-9", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      // Simulate successful snapshot load (with no restored game)
      actor ! TicTacToeActor.SnapshotLoaded(Right(None))

      // Verify actor is still alive
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe a[Right[_, _]]
    }

    "log error and continue when SnapshotLoaded fails" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-10", Seq(alice, bob), persistProbe.ref, dummyGameManager)
      val actor = spawn(behavior)

      // Simulate a snapshot load failure
      val ex = new RuntimeException("load failed") with NoStackTrace
      actor ! TicTacToeActor.SnapshotLoaded(Left(ex))

      // Verify actor is still alive by sending GetState
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe a[Right[_, _]]
    }

    "notify the GameManager when a game completes" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val (_, behavior) = TicTacToeActor.create("game-11", Seq(alice, bob), persistProbe.ref, gameManagerProbe.ref)
      val actor = spawn(behavior)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      val moves = Seq(
        (alice, Location(0, 0)), // X
        (bob, Location(1, 0)), // O
        (alice, Location(0, 1)), // X
        (bob, Location(1, 1)), // O
        (alice, Location(0, 2)) // X wins
      )

      moves.foreach { case (player, loc) =>
        actor ! TicTacToeActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
        val _ = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      }

      // Confirm GameManager was notified
      val completedMsg = gameManagerProbe.receiveMessage()
      completedMsg shouldBe GameManager.GameCompleted("game-11", GameLifecycleStatus.Completed)
    }
  }
}
