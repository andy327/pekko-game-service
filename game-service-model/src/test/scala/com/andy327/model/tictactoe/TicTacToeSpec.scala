package com.andy327.model.tictactoe

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class TicTacToeSpec extends AnyWordSpec with Matchers {
  "A TicTacToe game" should {
    "start with an empty board" in {
      val game = TicTacToe.empty("alice", "bob")
      game.board.flatten.flatten shouldBe empty
      game.currentPlayer shouldBe X
      game.gameStatus shouldBe InProgress
    }

    "allow a player to make a move" in {
      val game = TicTacToe.empty("alice", "bob")
      val result = game.play(X, Location(0, 0))
      result.isRight shouldBe true
      val newState = result.toOption.get
      newState.board(0)(0) shouldBe Some(X)
      newState.currentPlayer shouldBe O
      newState.gameStatus shouldBe InProgress
    }

    "not allow the wrong player to make a move" in {
      val game = TicTacToe.empty("alice", "bob").play(X, Location(0, 0)).toOption.get
      val result = game.play(X, Location(0, 1))
      result shouldBe Left(GameError.InvalidTurn)
    }

    "not allow a move on an occupied space" in {
      val game = TicTacToe.empty("alice", "bob").play(X, Location(0, 0)).toOption.get
      val result = game.play(O, Location(0, 0))
      result shouldBe Left(GameError.CellOccupied)
    }

    "not allow a move on an invalid cell" in {
      val game = TicTacToe.empty("alice", "bob")
      val result = game.play(X, Location(0, 4))
      result shouldBe Left(GameError.OutOfBounds)
    }

    "detect a winning condition" in {
      val game = TicTacToe.empty("alice", "bob")
        .play(X, Location(0, 0)).toOption.get
        .play(O, Location(1, 0)).toOption.get
        .play(X, Location(0, 1)).toOption.get
        .play(O, Location(1, 1)).toOption.get
        .play(X, Location(0, 2)).toOption.get // X wins

      game.gameStatus shouldBe Won(X)
    }

    "detect a draw" in {
      val game = TicTacToe.empty("alice", "bob")
        .play(X, Location(1, 1)).toOption.get
        .play(O, Location(0, 0)).toOption.get
        .play(X, Location(1, 0)).toOption.get
        .play(O, Location(1, 2)).toOption.get
        .play(X, Location(2, 1)).toOption.get
        .play(O, Location(0, 1)).toOption.get
        .play(X, Location(0, 2)).toOption.get
        .play(O, Location(2, 0)).toOption.get
        .play(X, Location(2, 2)).toOption.get // X draw

      game.gameStatus shouldBe Draw
    }

    "not allow a move after the game is won" in {
      val game = TicTacToe.empty("alice", "bob")
        .play(X, Location(0, 0)).toOption.get
        .play(O, Location(1, 0)).toOption.get
        .play(X, Location(0, 1)).toOption.get
        .play(O, Location(1, 1)).toOption.get
        .play(X, Location(0, 2)).toOption.get // X wins

      game.gameStatus shouldBe Won(X)
      val result = game.play(O, Location(1, 2))
      result shouldBe Left(GameError.GameOver)
    }

    "should properly render a game board" in {
      val game = TicTacToe.empty("alice", "bob")
        .play(X, Location(0, 0)).toOption.get
        .play(O, Location(1, 0)).toOption.get
        .play(X, Location(0, 1)).toOption.get
        .play(O, Location(2, 0)).toOption.get
        .play(X, Location(1, 1)).toOption.get

      game.toString shouldBe "-------\n|X|X| |\n|O|X| |\n|O| | |\n-------"
    }
  }
}
