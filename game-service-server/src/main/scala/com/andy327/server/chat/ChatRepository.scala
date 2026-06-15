package com.andy327.server.chat

import cats.effect.IO

import com.andy327.model.core.GameId
import com.andy327.server.actors.core.PlayerEvent

/** Repository for a bounded, recent-message history of each match's chat thread.
  *
  * Backed by a ring buffer (see [[RedisChatRepository]]): only the most recent N messages per game are retained, which
  * is all "backscroll" needs — a client opening a chat fetches recent context, while live messages continue to arrive
  * over the WebSocket. Writes are fire-and-forget from the caller's perspective; [[recent]] is served on demand by the
  * `GET /chat` endpoint and works for active and finished games alike.
  */
trait ChatRepository {

  /** Append a chat message to its game's history, evicting the oldest once the buffer is full. */
  def append(message: PlayerEvent.ChatMessage): IO[Unit]

  /** Load the recent chat history for `gameId`, oldest first. Empty if the game has no recorded messages. */
  def recent(gameId: GameId): IO[List[PlayerEvent.ChatMessage]]
}
