package com.andy327.actor.chat

import cats.effect.IO

import com.andy327.actor.core.PlayerEvent
import com.andy327.model.core.GameId

/** A [[ChatRepository]] that records nothing and returns no history.
  *
  * Used as the default where chat persistence is not wired (e.g. tests that don't exercise backscroll); the real
  * [[RedisChatRepository]] is supplied in production.
  */
object NoOpChatRepository extends ChatRepository {
  override def append(message: PlayerEvent.ChatMessage): IO[Unit] = IO.unit
  override def recent(gameId: GameId): IO[List[PlayerEvent.ChatMessage]] = IO.pure(Nil)
}
