package com.andy327.model.checkers

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameError, InProgress, PlayerId, Won}

class CheckersSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  /** Builds a game with only the given pieces on the board and `current` to move. */
  def gameWith(current: Color, pieces: (Square, Piece)*): Checkers = {
    val board = pieces.foldLeft(Vector.fill(Checkers.Size, Checkers.Size)(Option.empty[Piece])) {
      case (b, (sq, piece)) => b.updated(sq.row, b(sq.row).updated(sq.col, Some(piece)))
    }
    Checkers(alice, bob, board, currentPlayer = current, winner = None, moveCount = 0)
  }

  def pawn(color: Color): Piece = Piece(color, isKing = false)
  def king(color: Color): Piece = Piece(color, isKing = true)

  "A Checkers game" should {

    "resolve participants to their colors with playerFor" in {
      val game = Checkers.empty(alice, bob)
      game.playerFor(alice) shouldBe Some(Red)
      game.playerFor(bob) shouldBe Some(Black)
      game.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      Checkers.empty(alice, bob).players shouldBe List(alice, bob)
    }

    "start with twelve pawns per side and Red moving first" in {
      val game = Checkers.empty(alice, bob)
      game.board.flatten.flatten.count(_.color == Red) shouldBe 12
      game.board.flatten.flatten.count(_.color == Black) shouldBe 12
      game.board.flatten.flatten.forall(!_.isKing) shouldBe true
      game.currentPlayer shouldBe Red
      game.gameStatus shouldBe InProgress
    }

    "slide a pawn one square diagonally forward into an empty square" in {
      val Right(game) = Checkers.empty(alice, bob).play(Red, Move(Square(5, 0), List(Square(4, 1))))
      game.board(4)(1) shouldBe Some(pawn(Red))
      game.board(5)(0) shouldBe None
      game.currentPlayer shouldBe Black
      game.moveCount shouldBe 1
    }

    "reject a move when it is not the player's turn" in {
      val game = Checkers.empty(alice, bob)
      game.play(Black, Move(Square(2, 1), List(Square(3, 0)))) shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from an empty square" in {
      val game = Checkers.empty(alice, bob)
      game.play(Red, Move(Square(4, 1), List(Square(3, 0)))) shouldBe Left(NoPieceThere)
    }

    "reject a move of the opponent's piece" in {
      val game = Checkers.empty(alice, bob)
      game.play(Red, Move(Square(2, 1), List(Square(3, 0)))) shouldBe Left(NotYourPiece)
    }

    "reject a move that starts off the board" in {
      val game = Checkers.empty(alice, bob)
      game.play(Red, Move(Square(-1, 0), List(Square(0, 1)))) shouldBe Left(IllegalMove)
    }

    "reject a pawn sliding backward" in {
      val game = Checkers.empty(alice, bob)
      game.play(Red, Move(Square(5, 0), List(Square(6, 1)))) shouldBe Left(IllegalMove)
    }

    "require a capture when one is available" in {
      val game = gameWith(Red, Square(5, 2) -> pawn(Red), Square(4, 3) -> pawn(Black))
      // a simple slide is illegal while the jump is on offer
      game.play(Red, Move(Square(5, 2), List(Square(4, 1)))) shouldBe Left(CaptureRequired)
    }

    "capture an adjacent enemy by jumping to the empty square beyond" in {
      val game = gameWith(Red, Square(5, 2) -> pawn(Red), Square(4, 3) -> pawn(Black), Square(0, 7) -> pawn(Black))
      val Right(after) = game.play(Red, Move(Square(5, 2), List(Square(3, 4))))
      after.board(3)(4) shouldBe Some(pawn(Red))
      after.board(4)(3) shouldBe None // captured piece removed
      after.board(5)(2) shouldBe None
    }

    "chain a multi-jump in a single move, removing every jumped piece" in {
      val game = gameWith(
        Red,
        Square(5, 2) -> pawn(Red),
        Square(4, 3) -> pawn(Black),
        Square(2, 3) -> pawn(Black),
        Square(0, 7) -> pawn(Black) // keeps Black alive so the game continues
      )
      val Right(after) = game.play(Red, Move(Square(5, 2), List(Square(3, 4), Square(1, 2))))
      after.board(1)(2) shouldBe Some(pawn(Red))
      after.board(4)(3) shouldBe None
      after.board(2)(3) shouldBe None
    }

    "crown a pawn that slides onto the far row" in {
      val game = gameWith(Red, Square(1, 2) -> pawn(Red), Square(5, 0) -> pawn(Black))
      val Right(after) = game.play(Red, Move(Square(1, 2), List(Square(0, 1))))
      after.board(0)(1) shouldBe Some(king(Red))
      after.currentPlayer shouldBe Black
    }

    "crown a Black pawn on Black's far row (the last row)" in {
      val game = gameWith(Black, Square(6, 1) -> pawn(Black), Square(2, 5) -> pawn(Red))
      val Right(after) = game.play(Black, Move(Square(6, 1), List(Square(7, 0))))
      after.board(7)(0) shouldBe Some(king(Black))
      after.currentPlayer shouldBe Red
    }

    "stop a jump chain the moment a pawn is crowned" in {
      // (2,5) jumps (1,4) and lands on the king row at (0,3); a continuation over (1,2) must not be offered
      val game = gameWith(
        Red,
        Square(2, 5) -> pawn(Red),
        Square(1, 4) -> pawn(Black),
        Square(1, 2) -> pawn(Black)
      )
      val Right(after) = game.play(Red, Move(Square(2, 5), List(Square(0, 3))))
      after.board(0)(3) shouldBe Some(king(Red))
      after.board(1)(2) shouldBe Some(pawn(Black)) // not captured — the chain ended at crowning
      // the greedy two-step move is not legal
      game.play(Red, Move(Square(2, 5), List(Square(0, 3), Square(2, 1)))) shouldBe Left(IllegalMove)
    }

    "let a king move diagonally backward" in {
      val game = gameWith(Red, Square(4, 3) -> king(Red), Square(0, 7) -> pawn(Black))
      val Right(after) = game.play(Red, Move(Square(4, 3), List(Square(5, 4))))
      after.board(5)(4) shouldBe Some(king(Red))
    }

    "win when the opponent has no pieces left" in {
      val game = gameWith(Red, Square(5, 2) -> pawn(Red), Square(4, 3) -> pawn(Black))
      val Right(after) = game.play(Red, Move(Square(5, 2), List(Square(3, 4))))
      after.gameStatus shouldBe Won(Red)
    }

    "win when the opponent has pieces but no legal move" in {
      // A Black pawn stranded on the last row has both forward diagonals off the board, so it is stalemated
      val game = gameWith(Red, Square(5, 2) -> pawn(Red), Square(7, 0) -> pawn(Black))
      val Right(after) = game.play(Red, Move(Square(5, 2), List(Square(4, 3))))
      after.board(7)(0) shouldBe Some(pawn(Black)) // Black still has a piece
      after.gameStatus shouldBe Won(Red) // but no legal move, so Red wins
    }

    "reject a move after the game is won" in {
      val game = gameWith(Red, Square(5, 2) -> pawn(Red), Square(4, 3) -> pawn(Black))
      val Right(won) = game.play(Red, Move(Square(5, 2), List(Square(3, 4))))
      won.gameStatus shouldBe Won(Red)
      won.play(Black, Move(Square(3, 4), List(Square(4, 5)))) shouldBe Left(GameError.GameOver)
    }

    "forfeit to the opponent when a player leaves" in {
      val game = Checkers.empty(alice, bob)
      game.playerLeft(alice).map(_.gameStatus) shouldBe Right(Won(Black))
      game.playerLeft(bob).map(_.gameStatus) shouldBe Right(Won(Red))
    }

    "reject a leave from a non-participant" in {
      Checkers.empty(alice, bob).playerLeft(UUID.randomUUID()) shouldBe a[Left[_, _]]
    }

    "reject a leave once the game is already over" in {
      val game = gameWith(Red, Square(5, 2) -> pawn(Red), Square(4, 3) -> pawn(Black))
      val Right(won) = game.play(Red, Move(Square(5, 2), List(Square(3, 4))))
      won.gameStatus shouldBe Won(Red)
      won.playerLeft(bob) shouldBe Left(GameError.GameOver)
    }

    "render the board as a human-readable string" in {
      val game = gameWith(Red, Square(0, 1) -> king(Red), Square(5, 0) -> pawn(Black))
      val lines = game.render.split("\n")
      lines.head shouldBe "-" * (Checkers.Size * 2 + 1)
      lines(1) shouldBe "| |R| |.| |.| |.|" // king on (0,1); empty dark squares are ".", light squares blank
      lines(6) shouldBe "|b| |.| |.| |.| |" // Black pawn on (5,0)
    }
  }
}
