package com.andy327.model

package object tictactoe {
  type Board = Vector[Vector[Option[Mark]]]

  sealed trait Mark {
    def symbol: String
    override def toString: String = symbol
  }
  case object X extends Mark { val symbol = "X" }
  case object O extends Mark { val symbol = "O" }

  case class Location(row: Int, col: Int)
}
