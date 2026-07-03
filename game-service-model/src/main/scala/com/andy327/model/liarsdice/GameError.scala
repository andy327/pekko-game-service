package com.andy327.model.liarsdice

import com.andy327.model.core.GameError

// Liar's Dice-specific game errors. Errors shared by every turn-based game (InvalidPlayer, InvalidTurn, GameOver,
// Unknown) are defined once in core.GameError.

/** A bid was malformed — a non-positive quantity, or a numbered face outside 2–6. */
case object InvalidBid extends GameError {
  val message: String = "A bid must name a positive quantity and a face of 2–6, or a number of wild ones."
}

/** A bid did not legally raise the standing bid on the bidding track. */
case object IllegalRaise extends GameError {
  val message: String = "That bid does not raise the current bid."
}

/** A challenge was made with no standing bid to challenge — the opening move of a round must be a bid. */
case object NoBidToChallenge extends GameError {
  val message: String = "There is no bid to challenge yet."
}
