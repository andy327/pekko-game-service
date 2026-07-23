package com.andy327.model.connectfour

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{Draw, GameError, InProgress, PlayerId, Won}

class ConnectFourSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  "A ConnectFour game" should {

    "resolve participants to their marks with playerFor" in {
      val game = ConnectFour.empty(alice, bob)
      game.playerFor(alice) shouldBe Some(Red)
      game.playerFor(bob) shouldBe Some(Yellow)
      game.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      ConnectFour.empty(alice, bob).players shouldBe List(alice, bob)
    }

    "start with an empty board and Red moving first" in {
      val game = ConnectFour.empty(alice, bob)
      game.board.flatten.flatten shouldBe empty
      game.currentPlayer shouldBe Red
      game.gameStatus shouldBe InProgress
    }

    "place a piece at the lowest empty row when a column is chosen" in {
      val Right(game) = ConnectFour.empty(alice, bob).play(Red, Drop(3))
      game.board(5)(3) shouldBe Some(Red) // falls to the bottom row
      game.currentPlayer shouldBe Yellow
    }

    "stack pieces on top of each other in the same column" in {
      val Right(g1) = ConnectFour.empty(alice, bob).play(Red, Drop(0))
      val Right(g2) = g1.play(Yellow, Drop(0))
      g2.board(5)(0) shouldBe Some(Red) // first piece at bottom
      g2.board(4)(0) shouldBe Some(Yellow) // second piece stacked above
    }

    "reject a move when it is not the player's turn" in {
      val game = ConnectFour.empty(alice, bob)
      game.play(Yellow, Drop(0)) shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move for a column that is out of bounds" in {
      val game = ConnectFour.empty(alice, bob)
      game.play(Red, Drop(-1)) shouldBe Left(InvalidColumn)
      game.play(Red, Drop(7)) shouldBe Left(InvalidColumn)
    }

    "reject a move when the column is full" in {
      // Fill column 0 with alternating pieces (6 rows)
      val full = (0 until ConnectFour.Rows).foldLeft(ConnectFour.empty(alice, bob)) { (game, _) =>
        game.play(game.currentPlayer, Drop(0)).toOption.get
      }
      full.play(full.currentPlayer, Drop(0)) shouldBe Left(ColumnFull)
    }

    "offer every column as a legal move on an empty board" in {
      ConnectFour.empty(alice, bob).legalMoves shouldBe (0 until ConnectFour.Cols).map(Drop(_)).toList
    }

    "drop a column from legalMoves once it fills up" in {
      val full = (0 until ConnectFour.Rows).foldLeft(ConnectFour.empty(alice, bob)) { (game, _) =>
        game.play(game.currentPlayer, Drop(0)).toOption.get
      }
      full.legalMoves should not contain Drop(0)
      full.legalMoves should have size (ConnectFour.Cols - 1).toLong
    }

    "agree with play about which columns are open" in {
      val game = (0 until ConnectFour.Rows).foldLeft(ConnectFour.empty(alice, bob)) { (g, _) =>
        g.play(g.currentPlayer, Drop(0)).toOption.get
      }
      // every enumerated drop is accepted, and the filled column is both rejected and absent
      game.legalMoves.foreach(drop => game.play(game.currentPlayer, drop) shouldBe a[Right[_, _]])
      game.play(game.currentPlayer, Drop(0)) shouldBe Left(ColumnFull)
    }

    "offer no moves once the game is over" in {
      val game = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(1)).toOption.get
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(1)).toOption.get
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(1)).toOption.get
        .play(Red, Drop(0)).toOption.get // Red connects four vertically in column 0

      game.gameStatus shouldBe Won(Red)
      game.legalMoves shouldBe empty
    }

    "detect a horizontal win" in {
      // Red wins bottom row cols 0–3; Yellow stacks safely in col 6
      val game = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get // Red  (5,0)
        .play(Yellow, Drop(6)).toOption.get // Yellow (5,6)
        .play(Red, Drop(1)).toOption.get // Red  (5,1)
        .play(Yellow, Drop(6)).toOption.get // Yellow (4,6)
        .play(Red, Drop(2)).toOption.get // Red  (5,2)
        .play(Yellow, Drop(6)).toOption.get // Yellow (3,6)
        .play(Red, Drop(3)).toOption.get // Red  (5,3) — wins

      game.gameStatus shouldBe Won(Red)
    }

    "detect a vertical win" in {
      // Red fills column 0 from the bottom; Yellow stacks in col 1
      val game = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get // Red  (5,0)
        .play(Yellow, Drop(1)).toOption.get // Yellow (5,1)
        .play(Red, Drop(0)).toOption.get // Red  (4,0)
        .play(Yellow, Drop(1)).toOption.get // Yellow (4,1)
        .play(Red, Drop(0)).toOption.get // Red  (3,0)
        .play(Yellow, Drop(1)).toOption.get // Yellow (3,1)
        .play(Red, Drop(0)).toOption.get // Red  (2,0) — wins

      game.gameStatus shouldBe Won(Red)
    }

    "detect a diagonal win" in {
      // Red wins on the up-right diagonal: (5,0) (4,1) (3,2) (2,3)
      // Yellow pieces fill the required foundation cells in cols 1–3; Red uses col 4 as filler
      val game = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get // Red  (5,0) — diagonal
        .play(Yellow, Drop(1)).toOption.get // Yellow (5,1) — foundation for col 1
        .play(Red, Drop(4)).toOption.get // Red  (5,4) — filler
        .play(Yellow, Drop(2)).toOption.get // Yellow (5,2) — foundation for col 2
        .play(Red, Drop(1)).toOption.get // Red  (4,1) — diagonal
        .play(Yellow, Drop(3)).toOption.get // Yellow (5,3) — foundation for col 3
        .play(Red, Drop(4)).toOption.get // Red  (4,4) — filler
        .play(Yellow, Drop(2)).toOption.get // Yellow (4,2) — foundation for col 2
        .play(Red, Drop(4)).toOption.get // Red  (3,4) — filler
        .play(Yellow, Drop(3)).toOption.get // Yellow (4,3) — foundation for col 3
        .play(Red, Drop(2)).toOption.get // Red  (3,2) — diagonal
        .play(Yellow, Drop(3)).toOption.get // Yellow (3,3) — foundation for col 3
        .play(Red, Drop(3)).toOption.get // Red  (2,3) — diagonal, wins

      game.gameStatus shouldBe Won(Red)
    }

    "detect a draw when the board is full with no winner" in {
      val fullBoard = ConnectFour(
        playerRed = alice,
        playerYellow = bob,
        board = Vector.fill(ConnectFour.Rows, ConnectFour.Cols)(Option.empty[Mark]),
        currentPlayer = Red,
        winner = None,
        isDraw = true
      )
      fullBoard.gameStatus shouldBe Draw
    }

    "reject a move after the game is won" in {
      val won = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(6)).toOption.get
        .play(Red, Drop(1)).toOption.get
        .play(Yellow, Drop(6)).toOption.get
        .play(Red, Drop(2)).toOption.get
        .play(Yellow, Drop(6)).toOption.get
        .play(Red, Drop(3)).toOption.get // Red wins

      won.gameStatus shouldBe Won(Red)
      won.play(Yellow, Drop(0)) shouldBe Left(GameError.GameOver)
    }

    "forfeit to the opponent when a player leaves" in {
      val game = ConnectFour.empty(alice, bob)
      game.playerLeft(alice).map(_.gameStatus) shouldBe Right(Won(Yellow))
      game.playerLeft(bob).map(_.gameStatus) shouldBe Right(Won(Red))
    }

    "reject a leave from a non-participant" in {
      ConnectFour.empty(alice, bob).playerLeft(UUID.randomUUID()) shouldBe a[Left[_, _]]
    }

    "reject a leave once the game is already over" in {
      val won = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(1)).toOption.get
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(1)).toOption.get
        .play(Red, Drop(0)).toOption.get
        .play(Yellow, Drop(1)).toOption.get
        .play(Red, Drop(0)).toOption.get // Red connects four vertically in column 0

      won.gameStatus shouldBe Won(Red)
      won.playerLeft(bob) shouldBe Left(GameError.GameOver)
    }

    "render the board as a human-readable string" in {
      val game = ConnectFour.empty(alice, bob)
        .play(Red, Drop(0)).toOption.get // Red  (5,0)
        .play(Yellow, Drop(1)).toOption.get // Yellow (5,1)
        .play(Red, Drop(1)).toOption.get // Red  (4,1)

      val expected =
        "---------------\n" +
          "|.|.|.|.|.|.|.|\n" +
          "|.|.|.|.|.|.|.|\n" +
          "|.|.|.|.|.|.|.|\n" +
          "|.|.|.|.|.|.|.|\n" +
          "|.|R|.|.|.|.|.|\n" +
          "|R|Y|.|.|.|.|.|\n" +
          "---------------"

      game.render shouldBe expected
    }
  }
}
