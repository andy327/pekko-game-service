package com.andy327.actor.lobby

import com.andy327.model.core.RoomId

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
  case class LobbyNotFound(roomId: RoomId) extends LobbyError {
    def message: String = s"No such lobby: $roomId"
  }

  /** The player is already a member of the referenced lobby. Maps to HTTP 409. */
  case class AlreadyInLobby(roomId: RoomId) extends LobbyError {
    def message: String = s"Player is already in lobby $roomId"
  }

  /** The lobby has reached its maximum player count and cannot accept new players. Maps to HTTP 409. */
  case class LobbyFull(roomId: RoomId) extends LobbyError {
    def message: String = s"Cannot join lobby $roomId - lobby is full"
  }

  /** The lobby cannot be joined because the game has already started or ended. Maps to HTTP 409. */
  case class LobbyNotJoinable(roomId: RoomId) extends LobbyError {
    def message: String = s"Cannot join lobby $roomId - game has already started or ended"
  }

  /** The requesting player is not the host of the lobby and is not permitted to perform the action. Maps to HTTP 403.
    */
  case class NotHostError(roomId: RoomId) extends LobbyError {
    def message: String = s"Only the host can start game $roomId"
  }

  /** The lobby does not yet have enough players to start the game. Maps to HTTP 409. */
  case class LobbyNotReady(roomId: RoomId) extends LobbyError {
    def message: String = s"Lobby $roomId does not have enough players to start"
  }

  /** The game for this lobby has already started; use the game subscribe endpoint instead. Maps to HTTP 409. */
  case class GameAlreadyStarted(roomId: RoomId) extends LobbyError {
    def message: String = s"Game $roomId has already started; use the game subscribe endpoint instead"
  }

  /** The lobby's game is in progress, so players cannot leave it. Maps to HTTP 409. */
  case class GameInProgress(roomId: RoomId) extends LobbyError {
    def message: String = s"Cannot leave lobby $roomId - game is in progress"
  }

  /** The referenced player is not a bot seated in this lobby. Maps to HTTP 404. */
  case class NoSuchBot(roomId: RoomId) extends LobbyError {
    def message: String = s"No such bot in lobby $roomId"
  }
}
