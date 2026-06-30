package com.andy327.model

package object pig {

  /** A player action on their turn. */
  sealed trait PigMove

  /** Roll the die. The server supplies the result (1–6) so the model remains pure and testable. */
  case class Roll(result: Int) extends PigMove

  /** Bank the current turn score and pass to the next player. */
  case object Hold extends PigMove
}
