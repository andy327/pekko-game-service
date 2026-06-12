package com.andy327.model.connectfour

import com.andy327.model.core.GameError

// ConnectFour-specific game errors. Errors shared by multiple games (InvalidPlayer, InvalidTurn, GameOver, Unknown) are
// defined once in core.GameError.

/** The targeted column is outside the 7-column board. */
case object InvalidColumn extends GameError {
  val message = "Column is out of bounds."
}

/** The targeted column has no empty cells left. */
case object ColumnFull extends GameError {
  val message = "That column is full."
}
