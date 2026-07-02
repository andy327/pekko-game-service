package com.andy327.model.mastermind

import com.andy327.model.core.GameError

// Mastermind-specific game errors. Errors shared by every turn-based game (InvalidPlayer, InvalidTurn, GameOver,
// Unknown) are defined once in core.GameError.

/** A code or guess did not contain exactly [[Mastermind.CodeLength]] pegs. */
case object InvalidCodeLength extends GameError {
  val message: String = s"A code must be exactly ${Mastermind.CodeLength} pegs."
}

/** A SetCode arrived after the secret code was already fixed. */
case object CodeAlreadySet extends GameError {
  val message = "The secret code has already been set."
}

/** A Guess arrived before the codemaker had set the secret code. */
case object CodeNotSet extends GameError {
  val message = "The secret code has not been set yet."
}
