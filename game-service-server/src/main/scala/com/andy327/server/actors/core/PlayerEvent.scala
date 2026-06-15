package com.andy327.server.actors.core

import java.time.Instant

import com.andy327.model.core.{GameId, PlayerId}
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

  /** The game has ended.
    *
    * @param result Completed (someone won) or Cancelled (host left before the game started).
    */
  final case class GameEnded(result: GameLifecycleStatus.GameEnded) extends PlayerEvent

  /** A chat message in a match's thread, pushed to everyone watching that game (in lobby or in progress).
    *
    * @param gameId the match the message belongs to
    * @param senderId the authenticated player who sent it
    * @param senderName the sender's display name, denormalized so clients can render without a lookup
    * @param text the message body
    * @param sentAt server timestamp when the message was accepted
    */
  final case class ChatMessage(gameId: GameId, senderId: PlayerId, senderName: String, text: String, sentAt: Instant)
      extends PlayerEvent
}
