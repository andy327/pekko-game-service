package com.andy327.model.tictactoe

import com.andy327.model.core.{GameError => CoreGameError, PlayerId}

/**
 * Game-specific error types for TicTacToe.
 *
 * These errors represent invalid game actions or states, such as attempting to move out of turn, making an illegal
 * move, or interacting with a finished game.
 *
 * Each case object or class provides a user-friendly message describing the issue.
 */
sealed trait GameError extends CoreGameError

object GameError {
  case class InvalidPlayer(playerId: PlayerId) extends GameError {
    val message: String = s"Unknown player: $playerId"
  }

  case object InvalidTurn extends GameError {
    val message = "It's not your turn."
  }

  case object CellOccupied extends GameError {
    val message = "This cell is already occupied."
  }

  case object OutOfBounds extends GameError {
    val message = "Move is out of bounds."
  }

  case object GameOver extends GameError {
    val message = "The game is already over."
  }

  case class Unknown(message: String) extends GameError
}
