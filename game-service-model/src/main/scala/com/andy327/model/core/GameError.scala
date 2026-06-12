package com.andy327.model.core

/** Represents an error that can occur during game processing.
  *
  * GameErrors are used to communicate failure reasons when operations like making a move or starting a game cannot be
  * completed successfully.
  *
  * Errors shared by every turn-based game (wrong player, wrong turn, game already over) are defined once in the
  * companion object; errors specific to one game's rules (e.g. `CellOccupied`, `ColumnFull`) live in that game's
  * package and extend this trait directly.
  */
trait GameError {
  def message: String
}

object GameError {

  /** A generic catch-all error, used when a more specific error is not available.
    *
    * @param message explanation of the failure
    */
  case class Unknown(message: String) extends GameError

  /** The acting player is not a participant in this game. */
  case class InvalidPlayer(playerId: PlayerId) extends GameError {
    val message: String = s"Unknown player: $playerId"
  }

  /** The move was made out of turn. */
  case object InvalidTurn extends GameError {
    val message = "It's not your turn."
  }

  /** The game has already reached a terminal state and accepts no further moves. */
  case object GameOver extends GameError {
    val message = "The game is already over."
  }
}
