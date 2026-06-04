package com.andy327.model

package object tictactoe {

  /** The 3×3 game board, represented as a row-major vector of cells. Each cell holds the mark of the player who
    * claimed it, or None if unclaimed.
    */
  type Board = Vector[Vector[Option[Mark]]]

  /** Identifies which player owns a cell. X always moves first. */
  sealed trait Mark extends com.andy327.model.core.Mark
  case object X extends Mark { val symbol = "X" }
  case object O extends Mark { val symbol = "O" }

  /** A zero-based (row, col) coordinate on the board, where (0,0) is the top-left cell. */
  case class Location(row: Int, col: Int)
}
