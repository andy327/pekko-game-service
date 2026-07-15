package com.andy327.model.checkers

import com.andy327.model.core.GameError

// Checkers-specific game errors. Errors shared by multiple games (InvalidPlayer, InvalidTurn, GameOver, Unknown) are
// defined once in core.GameError.

/** There is no piece on the move's starting square. */
case object NoPieceThere extends GameError {
  val message = "There is no piece on that square."
}

/** The piece on the starting square belongs to the other player. */
case object NotYourPiece extends GameError {
  val message = "That piece isn't yours."
}

/** A capture is available, so a non-capturing move may not be played. */
case object CaptureRequired extends GameError {
  val message = "A capture is available and must be taken."
}

/** The move is not legal for this piece — wrong direction, off the board, blocked, or an incomplete jump chain. */
case object IllegalMove extends GameError {
  val message = "That move isn't legal."
}
