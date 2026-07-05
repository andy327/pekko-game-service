package com.andy327.server.auth

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO

import dev.profunktor.redis4cats.RedisCommands

/** An [[AuthRateLimiter]] backed by Redis.
  *
  * Throttle counters and failure counters are Redis keys incremented with `INCR`, given a TTL equal to their window on
  * first hit (so the window is fixed, not sliding). Locks are `SETEX` keys whose presence — and remaining TTL — is the
  * lockout. Because every key carries a TTL, the store self-prunes and survives restarts without cleanup.
  *
  * Keys are namespaced under `auth-rl:` so they never collide with the lobby/chat/game keyspaces sharing this Redis.
  *
  * @param redis Redis commands handle used for all reads and writes
  */
class RedisAuthRateLimiter(redis: RedisCommands[IO, String, String]) extends AuthRateLimiter {
  import RedisAuthRateLimiter._

  override def throttle(key: String, limit: Int, window: FiniteDuration): IO[RateLimitOutcome] = {
    val k = countKey(key)
    redis.incr(k).flatMap { count =>
      val ensureTtl = if (count == 1L) redis.expire(k, window).void else IO.unit
      ensureTtl *> {
        if (count > limit) redis.ttl(k).map(ttl => RateLimitOutcome.Limited(ttl.getOrElse(window)))
        else IO.pure(RateLimitOutcome.Allowed)
      }
    }
  }

  override def lockStatus(key: String): IO[Option[FiniteDuration]] =
    redis.ttl(lockKey(key)).map(_.filter(_.length > 0))

  override def recordFailure(
      key: String,
      threshold: Int,
      window: FiniteDuration,
      lockout: FiniteDuration
  ): IO[Unit] = {
    val fk = failKey(key)
    redis.incr(fk).flatMap { count =>
      val ensureTtl = if (count == 1L) redis.expire(fk, window).void else IO.unit
      ensureTtl *> {
        if (count >= threshold) redis.setEx(lockKey(key), "1", lockout) *> redis.del(fk).void
        else IO.unit
      }
    }
  }

  override def clearFailures(key: String): IO[Unit] =
    redis.del(failKey(key), lockKey(key)).void
}

object RedisAuthRateLimiter {
  private val Prefix = "auth-rl:"
  private def countKey(key: String): String = s"${Prefix}count:$key"
  private def failKey(key: String): String = s"${Prefix}fail:$key"
  private def lockKey(key: String): String = s"${Prefix}lock:$key"
}
