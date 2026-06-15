package com.andy327.server.chat

import cats.effect.IO

import com.andy327.model.core.GameId
import com.andy327.server.actors.core.PlayerEvent

/** A [[ChatRepository]] that records nothing and returns no history.
  *
  * Used as the default where chat persistence is not wired (e.g. tests that don't exercise backscroll); the real
  * [[RedisChatRepository]] is supplied in production.
  */
object NoOpChatRepository extends ChatRepository {
  override def append(message: PlayerEvent.ChatMessage): IO[Unit] = IO.unit
  override def recent(gameId: GameId): IO[List[PlayerEvent.ChatMessage]] = IO.pure(Nil)
}
