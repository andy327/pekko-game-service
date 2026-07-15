package com.andy327.model

package object checkers {

  /** The 8×8 game board, represented as a row-major vector of cells.
    *
    * Row 0 is the top (Black's home rows); row 7 is the bottom (Red's home rows). Each cell holds the [[Piece]] standing
    * there or None if the cell is empty. Only dark squares — those where `(row + col)` is odd — are ever occupied.
    */
  type Board = Vector[Vector[Option[Piece]]]

  /** Identifies which side a piece belongs to.
    *
    * Red occupies the bottom three rows and moves upward (toward row 0); Black occupies the top three rows and moves
    * downward (toward row 7). Red moves first.
    */
  sealed trait Color extends com.andy327.model.core.Mark
  case object Red extends Color { val symbol = "R" }
  case object Black extends Color { val symbol = "B" }

  /** A single checker: its owning [[Color]] and whether it has been crowned a king.
    *
    * A pawn moves and captures only diagonally forward; a king moves and captures diagonally in all four directions.
    */
  case class Piece(color: Color, isKing: Boolean)

  /** A board coordinate. `row` increases downward from 0 (top) to 7 (bottom); `col` increases left to right. */
  case class Square(row: Int, col: Int)

  /** A move: the piece's starting square followed by the ordered squares it lands on.
    *
    * A simple slide has a single landing square one diagonal step away. A capture has a landing square two steps away
    * (jumping the captured piece); a multi-jump lists one landing square per capture, in order. Because captures are
    * mandatory and a multi-jump must be played to completion, a submitted move must spell out the entire jump chain.
    */
  case class Move(from: Square, steps: List[Square])
}
