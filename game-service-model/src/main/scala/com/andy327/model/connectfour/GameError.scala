package com.andy327.model.connectfour

import com.andy327.model.core.{GameError => CoreGameError, PlayerId}

/** Game-specific error types for ConnectFour.
  *
  * These errors represent invalid game actions or states, such as dropping into a full column, moving out of turn, or
  * interacting with a finished game.
  */
sealed trait GameError extends CoreGameError

object GameError {

  case class InvalidPlayer(playerId: PlayerId) extends GameError {
    val message: String = s"Unknown player: $playerId"
  }

  case object InvalidTurn extends GameError {
    val message = "It's not your turn."
  }

  case object InvalidColumn extends GameError {
    val message = "Column is out of bounds."
  }

  case object ColumnFull extends GameError {
    val message = "That column is full."
  }

  case object GameOver extends GameError {
    val message = "The game is already over."
  }
}
