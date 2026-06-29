package com.andy327.actor.core

import java.time.Instant

import com.andy327.actor.game.GameState
import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyMetadata}
import com.andy327.model.core.{PlayerId, RoomId}

/** Events pushed from the server to a connected player over WebSocket.
  *
  * PlayerActors receive these from LobbyManager and game actors and forward them as JSON TextMessages to the client.
  */
sealed trait PlayerEvent

object PlayerEvent {

  /** The lobby the player is in has changed — a player joined, left, or the status updated.
    *
    * @param spectatorCount connected subscribers who are not seated in `metadata.players` — i.e. watching but not
    *                       playing
    */
  final case class LobbyUpdated(metadata: LobbyMetadata, spectatorCount: Int) extends PlayerEvent

  /** The game state changed after a move — carries the full updated board state.
    *
    * @param roomId the room this update belongs to; lets a client routing several rooms dispatch the push to the right
    *               one (a room hosts at most one live match at a time)
    * @param state the full updated board state, rendered for the receiving subscriber
    * @param spectatorCount connected subscribers who are not one of the match's seated players
    */
  final case class GameStateUpdated(roomId: RoomId, state: GameState, spectatorCount: Int) extends PlayerEvent

  /** The game has ended.
    *
    * @param result Completed (someone won or drew), or Cancelled — the host left/cancelled a pre-game lobby, or a
    *               post-game room was evicted for sitting idle/empty too long.
    */
  final case class GameEnded(result: GameLifecycleStatus.GameEnded) extends PlayerEvent

  /** A chat message in a match's thread, pushed to everyone watching that game (in lobby or in progress).
    *
    * @param roomId the room the message belongs to
    * @param senderId the authenticated player who sent it
    * @param senderName the sender's display name, denormalized so clients can render without a lookup
    * @param text the message body
    * @param sentAt server timestamp when the message was accepted
    */
  final case class ChatMessage(roomId: RoomId, senderId: PlayerId, senderName: String, text: String, sentAt: Instant)
      extends PlayerEvent
}
