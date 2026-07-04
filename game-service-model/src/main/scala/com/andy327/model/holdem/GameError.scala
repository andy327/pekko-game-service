package com.andy327.model.holdem

import com.andy327.model.core.GameError

// Texas Hold 'Em-specific game errors. Errors shared by every turn-based game (InvalidPlayer, InvalidTurn, GameOver,
// Unknown) are defined once in core.GameError.

/** A bet was attempted while there was already a bet to match — the player must call or raise instead. */
case object BetNotAllowed extends GameError {
  val message: String = "There is already a bet; call or raise instead."
}

/** A raise was attempted with no bet outstanding — the player must bet to open the action. */
case object RaiseNotAllowed extends GameError {
  val message: String = "There is no bet to raise; bet to open the action."
}

/** A check was attempted while facing a bet — the player must call, raise, or fold. */
case object CannotCheck extends GameError {
  val message: String = "You cannot check while facing a bet."
}

/** An opening bet was smaller than the big blind (and not an all-in for the player's whole stack). */
case object BetTooSmall extends GameError {
  val message: String = "A bet must be at least the big blind."
}

/** A raise was smaller than the minimum legal raise (and not an all-in for the player's whole stack). */
case object RaiseTooSmall extends GameError {
  val message: String = "A raise must be at least the size of the previous bet or raise."
}

/** An action would commit more chips than the player has. */
case object InsufficientChips extends GameError {
  val message: String = "You do not have enough chips for that action."
}
