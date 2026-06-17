package com.andy327.model.battleship

import com.andy327.model.core.GameError

// Battleship-specific game errors. Errors shared by every turn-based game (InvalidPlayer, InvalidTurn, GameOver,
// Unknown) are defined once in core.GameError.

/** The target coordinate lies outside the board. */
case object OutOfBounds extends GameError {
  val message = "Target coordinate is outside the board."
}

/** The target coordinate has already been fired at. */
case object AlreadyFired extends GameError {
  val message = "You have already fired at this coordinate."
}
