package com.andy327.server.auth

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO

import dev.profunktor.redis4cats.RedisCommands

/** A [[RevocationStore]] backed by Redis.
  *
  * Each account's cutoff is a single key holding the cutoff as epoch millis, written with `SETEX` so it expires after
  * the token lifetime — after which every token predating the cutoff has expired anyway. Keys are namespaced under
  * `auth:revoked-before:` so they don't collide with the other Redis keyspaces.
  *
  * @param redis Redis commands handle used for all reads and writes
  */
class RedisRevocationStore(redis: RedisCommands[IO, String, String]) extends RevocationStore {
  import RedisRevocationStore.key

  override def revokeBefore(accountId: UUID, cutoff: Instant, ttl: FiniteDuration): IO[Unit] =
    redis.setEx(key(accountId), cutoff.toEpochMilli.toString, ttl)

  override def revokedBefore(accountId: UUID): IO[Option[Instant]] =
    redis.get(key(accountId)).map(_.flatMap(_.toLongOption).map(Instant.ofEpochMilli))
}

object RedisRevocationStore {
  private def key(accountId: UUID): String = s"auth:revoked-before:$accountId"
}
