package com.andy327.model.core

/**
 * Represents an error that can occur during game processing.
 *
 * GameErrors are used to communicate failure reasons when operations like making a move or starting a game cannot be
 * completed successfully.
 */
trait GameError {
  def message: String
}
object GameError {

  /**
   * A generic catch-all error, used when a more specific error is not available.
   *
   * @param message explanation of the failure
   */
  case class Unknown(message: String) extends GameError
}
