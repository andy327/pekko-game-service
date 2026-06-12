package com.andy327.model.tictactoe

import com.andy327.model.core.GameError

// TicTacToe-specific game errors. Errors shared by multiple games (InvalidPlayer, InvalidTurn, GameOver, Unknown) are
// defined once in core.GameError.

/** The targeted cell already contains a mark. */
case object CellOccupied extends GameError {
  val message = "This cell is already occupied."
}

/** The move coordinates are outside the 3×3 board. */
case object OutOfBounds extends GameError {
  val message = "Move is out of bounds."
}
