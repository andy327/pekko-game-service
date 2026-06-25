package com.andy327.actor.lobby

import com.andy327.model.core.GameId

/** Represents a typed error that can occur during lobby or game lifecycle operations.
  *
  * LobbyError cases are returned by the GameManager in place of unstructured error strings, allowing routes to map each
  * error to an appropriate HTTP status code (e.g. 404 for not found, 409 for conflict, 403 for authorization failures).
  */
sealed trait LobbyError {
  def message: String
}

object LobbyError {

  /** The referenced lobby does not exist. Maps to HTTP 404. */
  case class LobbyNotFound(gameId: GameId) extends LobbyError {
    def message: String = s"No such lobby: $gameId"
  }

  /** The player is already a member of the referenced lobby. Maps to HTTP 409. */
  case class AlreadyInLobby(gameId: GameId) extends LobbyError {
    def message: String = s"Player is already in lobby $gameId"
  }

  /** The lobby has reached its maximum player count and cannot accept new players. Maps to HTTP 409. */
  case class LobbyFull(gameId: GameId) extends LobbyError {
    def message: String = s"Cannot join lobby $gameId - lobby is full"
  }

  /** The lobby cannot be joined because the game has already started or ended. Maps to HTTP 409. */
  case class LobbyNotJoinable(gameId: GameId) extends LobbyError {
    def message: String = s"Cannot join lobby $gameId - game has already started or ended"
  }

  /** The requesting player is not the host of the lobby and is not permitted to perform the action. Maps to HTTP 403.
    */
  case class NotHostError(gameId: GameId) extends LobbyError {
    def message: String = s"Only the host can start game $gameId"
  }

  /** The lobby does not yet have enough players to start the game. Maps to HTTP 409. */
  case class LobbyNotReady(gameId: GameId) extends LobbyError {
    def message: String = s"Lobby $gameId does not have enough players to start"
  }

  /** The game for this lobby has already started; use the game subscribe endpoint instead. Maps to HTTP 409. */
  case class GameAlreadyStarted(gameId: GameId) extends LobbyError {
    def message: String = s"Game $gameId has already started; use the game subscribe endpoint instead"
  }

  /** The lobby's game is in progress, so players cannot leave it. Maps to HTTP 409. */
  case class GameInProgress(gameId: GameId) extends LobbyError {
    def message: String = s"Cannot leave lobby $gameId - game is in progress"
  }
}
