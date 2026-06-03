package com.andy327.model

package object connectfour {

  /** The 6×7 game board, represented as a row-major vector of cells.
    *
    * Row 0 is the top; row 5 is the bottom. Each cell holds the mark of the player who dropped a piece there, or None
    * if the cell is empty.
    */
  type Board = Vector[Vector[Option[Mark]]]

  /** Identifies which player owns a cell.
    *
    * Red always moves first.
    */
  sealed trait Mark {
    def symbol: String
    override def toString: String = symbol
  }
  case object Red extends Mark { val symbol = "R" }
  case object Yellow extends Mark { val symbol = "Y" }

  /** A move in ConnectFour: drop a piece into the given column.
    *
    * The piece falls to the lowest empty row.
    */
  case class Drop(col: Int)
}
