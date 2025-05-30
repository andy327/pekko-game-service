package com.andy327.model.tictactoe

import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.matchers.should.Matchers
import com.andy327.model.tictactoe._

class TicTacToeSpec extends AnyWordSpec with Matchers {
  "A TicTacToe game" should {
    "start with an empty board" in {
      val game = TicTacToe.empty
      game.board.flatten.flatten shouldBe empty
      game.currentPlayer shouldBe X
      game.status shouldBe InProgress
    }

    "allow a player to make a move" in {
      val game = TicTacToe.empty
      val result = game.play(Location(0, 0))
      result.isRight shouldBe true
      val newState = result.toOption.get
      newState.board(0)(0) shouldBe Some(X)
      newState.currentPlayer shouldBe O
      newState.status shouldBe InProgress
    }

    "not allow a move on an occupied space" in {
      val game = TicTacToe.empty.play(Location(0, 0)).toOption.get
      val result = game.play(Location(0, 0))
      result.isLeft shouldBe true
    }

    "detect a winning condition" in {
      val game = TicTacToe.empty
        .play(Location(0, 0)).toOption.get // X
        .play(Location(1, 0)).toOption.get // O
        .play(Location(0, 1)).toOption.get // X
        .play(Location(1, 1)).toOption.get // O
        .play(Location(0, 2)).toOption.get // X wins

      game.status shouldBe Won(X)
    }

    "detect a draw" in {
      val game = TicTacToe.empty
        .play(Location(1, 1)).toOption.get // X
        .play(Location(0, 0)).toOption.get // O
        .play(Location(1, 0)).toOption.get // X
        .play(Location(1, 2)).toOption.get // O
        .play(Location(2, 1)).toOption.get // X
        .play(Location(0, 1)).toOption.get // O
        .play(Location(0, 2)).toOption.get // X
        .play(Location(2, 0)).toOption.get // O
        .play(Location(2, 2)).toOption.get // X draw

      game.status shouldBe Draw
    }

    "not allow a move after the game is won" in {
      val game = TicTacToe.empty
        .play(Location(0, 0)).toOption.get // X
        .play(Location(1, 0)).toOption.get // O
        .play(Location(0, 1)).toOption.get // X
        .play(Location(1, 1)).toOption.get // O
        .play(Location(0, 2)).toOption.get // X wins

      game.status shouldBe Won(X)
      val result = game.play(Location(1, 2)) // O
      result.isLeft shouldBe true
    }
  }
}
