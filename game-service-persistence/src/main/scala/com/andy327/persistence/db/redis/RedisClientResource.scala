package com.andy327.persistence.db.redis

import org.slf4j.LoggerFactory

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.IO
import cats.effect.kernel.Resource

import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.{Redis, RedisCommands}

/** Builds a managed redis4cats `RedisCommands` resource from application config.
  *
  * The Redis URI is read from `pekko-game-service.redis.uri` and falls back to `redis://localhost:6379` in the
  * reference config. The returned `Resource` handles connection lifecycle — connect on open, disconnect on release.
  */
object RedisClientResource {

  implicit private val log: Log[IO] = new Log[IO] {
    private val logger = LoggerFactory.getLogger("RedisClientResource")
    override def debug(msg: => String): IO[Unit] = IO(logger.debug(msg))
    override def info(msg: => String): IO[Unit] = IO(logger.info(msg))
    override def error(msg: => String): IO[Unit] = IO(logger.error(msg))
  }

  /** @param config Typesafe Config to read Redis settings from; defaults to the application classpath config */
  def apply(config: Config = ConfigFactory.load()): Resource[IO, RedisCommands[IO, String, String]] = {
    val uri = config.getString("pekko-game-service.redis.uri")
    Redis[IO].utf8(uri)
  }
}
