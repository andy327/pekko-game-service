package com.andy327.actor.chat

import org.slf4j.LoggerFactory

import cats.effect.IO

import dev.profunktor.redis4cats.RedisCommands

import com.andy327.actor.core.PlayerEvent
import com.andy327.model.core.GameId

/** A [[ChatRepository]] backed by a per-game Redis list used as a fixed-size ring buffer.
  *
  * Each message is `LPUSH`ed onto the head of `chat:{gameId}` and the list is immediately `LTRIM`med to the newest
  * `maxMessages` entries, so storage per game is bounded regardless of how chatty a match gets. [[recent]] reads the
  * buffer with `LRANGE` and reverses it to oldest-first display order. Corrupt entries are skipped with a warning
  * rather than failing the whole read.
  *
  * @param redis Redis commands handle used for all reads and writes
  * @param maxMessages the most recent messages retained per game
  */
class RedisChatRepository(redis: RedisCommands[IO, String, String], maxMessages: Int = 100) extends ChatRepository {

  private val logger = LoggerFactory.getLogger(getClass)

  private def chatKey(gameId: GameId): String = s"chat:$gameId"

  override def append(message: PlayerEvent.ChatMessage): IO[Unit] = {
    val key = chatKey(message.gameId)
    redis.lPush(key, ChatCodecs.serialize(message)) *> redis.lTrim(key, 0, maxMessages - 1L)
  }

  override def recent(gameId: GameId): IO[List[PlayerEvent.ChatMessage]] =
    redis.lRange(chatKey(gameId), 0, maxMessages - 1L).map { raw =>
      raw.reverse.flatMap { json =>
        ChatCodecs.deserialize(json) match {
          case Right(message) => Some(message)
          case Left(err)      =>
            logger.warn(s"Skipping corrupt chat message for $gameId: ${err.getMessage}")
            None
        }
      }
    }
}
