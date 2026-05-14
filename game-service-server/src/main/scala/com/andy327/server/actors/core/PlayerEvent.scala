package com.andy327.server.actors.core

import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyMetadata}

/** Events pushed from the server to a connected player over WebSocket.
  *
  * PlayerActors receive these from LobbyManager and game actors and forward them as JSON TextMessages to the client.
  */
sealed trait PlayerEvent

object PlayerEvent {

  /** The lobby the player is in has changed — a player joined, left, or the status updated. */
  final case class LobbyUpdated(metadata: LobbyMetadata) extends PlayerEvent

  /** The game state changed after a move — carries the full updated board state. */
  final case class GameStateUpdated(state: GameState) extends PlayerEvent

  /** The game has ended. @param result Completed (someone won) or Cancelled (host left before the game started). */
  final case class GameEnded(result: GameLifecycleStatus.GameEnded) extends PlayerEvent
}
