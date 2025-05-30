package com.andy327.model

package object tictactoe {
  type Board = Vector[Vector[Option[Mark]]]

  sealed trait Mark {
    def symbol: String
    override def toString: String = symbol
  }
  case object X extends Mark { val symbol = "X" }
  case object O extends Mark { val symbol = "O" }

  case class Location(row: Int, col: Int) {
    require(row >= 0 && row <= 2, s"row out of bounds: $row")
    require(col >= 0 && col <= 2, s"col out of bounds: $col")
  }

  sealed trait GameStatus
  case object InProgress extends GameStatus
  case class Won(winner: Mark) extends GameStatus
  case object Draw extends GameStatus
}
