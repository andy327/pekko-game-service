package com.andy327.server.actors.tictactoe

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.tictactoe.{GameError, Location, O, TicTacToe, X}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameState, TicTacToeState}

class TicTacToeActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "TicTacToeActor" should {
    "return an empty 3×3 board on GetState" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game‑1", Seq("alice", "bob"), persistProbe.ref)
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
        TicTacToeActor.create("bad-game", Seq("onlyOnePlayer"), persistProbe.ref)
      }

      thrown.getMessage should include("Tic‑Tac‑Toe needs exactly two players")
    }

    "restore state from snapshot correctly using fromSnapshot" in {
      val snapshotState = TicTacToe(
        playerX = "alice",
        playerO = "bob",
        board = Vector(
          Vector(Some(X), None, None),
          Vector(None, Some(O), None),
          Vector(None, None, None)
        ),
        currentPlayer = X,
        winner = None,
        isDraw = false
      )

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()

      // build behavior from snapshot and spawn it
      val behavior = TicTacToeActor.fromSnapshot("snap‑game", snapshotState, persistProbe.ref)
      val actor = spawn(behavior)

      // ask for state and verify it matches the snapshot
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val result = replyProbe.receiveMessage()

      result match {
        case Right(TicTacToeState(board, current, winner, draw)) =>
          board(0)(0) shouldBe "X"
          board(1)(1) shouldBe "O"
          current shouldBe "X"
          winner shouldBe None
          draw shouldBe false
        case other =>
          fail(s"Unexpected response: $other")
      }
    }

    "apply a valid move and switch currentPlayer" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game‑2", Seq("alice", "bob"), persistProbe.ref)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Alice (X) plays (0,0)
      actor ! TicTacToeActor.MakeMove("alice", Location(0, 0), replyProbe.ref)
      val state1 = replyProbe.receiveMessage()

      state1 match {
        case Right(TicTacToeState(board, current, _, _)) =>
          board(0)(0) shouldBe "X"
          current shouldBe "O"
        case other => fail(s"Unexpected: $other")
      }

      // Bob (O) plays (1,1)
      actor ! TicTacToeActor.MakeMove("bob", Location(1, 1), replyProbe.ref)
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
      val (_, behavior) = TicTacToeActor.create("game‑3", Seq("alice", "bob"), persistProbe.ref)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Bob tries to move first (but it's Alice's turn)
      actor ! TicTacToeActor.MakeMove("bob", Location(0, 0), replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from a player not in the game" in {
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create("game-4", Seq("alice", "bob"), persistProbe.ref)
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.MakeMove("eve", Location(0, 0), replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result shouldBe Left(GameError.InvalidPlayer(s"Player ID 'eve' is not part of this game."))
    }
  }
}
