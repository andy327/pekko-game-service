package com.andy327.server.actors.core

import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyMetadata}

/** Events pushed from the server to a connected player. */
sealed trait PlayerEvent

object PlayerEvent {
  final case class LobbyUpdated(metadata: LobbyMetadata) extends PlayerEvent
  final case class GameStateUpdated(state: GameState) extends PlayerEvent
  final case class GameEnded(result: GameLifecycleStatus.GameEnded) extends PlayerEvent
}
