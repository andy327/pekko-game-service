package com.andy327.model.tictactoe

sealed trait GameError {
  def message: String
}
object GameError {
  case class InvalidPlayer(player: String) extends GameError {
    val message: String = s"Unknown player: $player"
  }
  case object InvalidTurn extends GameError {
    val message = "It's not your turn."
  }
  case object CellOccupied extends GameError {
    val message = "This cell is already occupied."
  }
  case object OutOfBounds extends GameError {
    val message = "Move is out of bounds."
  }
  case object GameOver extends GameError {
    val message = "The game is already over."
  }
  case class Unknown(message: String) extends GameError
}
