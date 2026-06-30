package com.andy327.model.pig

import com.andy327.model.core.GameError

/** The player attempted to Hold without having rolled at all this turn. */
case object NothingToHold extends GameError {
  val message = "You must roll at least once before holding."
}
