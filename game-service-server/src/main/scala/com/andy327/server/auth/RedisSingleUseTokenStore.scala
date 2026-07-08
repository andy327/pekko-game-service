package com.andy327.server.auth

import java.util.UUID

import scala.concurrent.duration.FiniteDuration
import scala.util.Try

import cats.effect.IO

import dev.profunktor.redis4cats.RedisCommands

/** A [[SingleUseTokenStore]] backed by Redis.
  *
  * Each token is one key holding the account id, written with `SETEX` so it expires after the token TTL. The key is the
  * purpose-namespaced hash of the token (never the raw value), under the `auth:token:` keyspace so it doesn't collide
  * with the other Redis namespaces.
  *
  * Redemption reads the value and then deletes the key, using `DEL`'s removed-count for single-use safety: Redis runs
  * each command atomically, so if two requests race to redeem the same token, exactly one `DEL` returns 1 (the winner)
  * and the rest return 0 and are rejected. A token can therefore never be redeemed twice, even concurrently.
  *
  * @param redis Redis commands handle used for all reads and writes
  */
class RedisSingleUseTokenStore(redis: RedisCommands[IO, String, String]) extends SingleUseTokenStore {
  import RedisSingleUseTokenStore.key

  override def issue(purpose: TokenPurpose, accountId: UUID, ttl: FiniteDuration): IO[String] =
    IO(SingleUseTokenStore.generateToken()).flatTap(raw => redis.setEx(key(purpose, raw), accountId.toString, ttl))

  override def consume(purpose: TokenPurpose, rawToken: String): IO[Option[UUID]] = {
    val k = key(purpose, rawToken)
    redis.get(k).flatMap {
      case Some(value) =>
        // Only the caller whose DEL actually removed the key (count 1) may redeem it; concurrent racers see 0.
        redis.del(k).map(removed => if (removed > 0) Try(UUID.fromString(value)).toOption else None)
      case None => IO.pure(None)
    }
  }
}

object RedisSingleUseTokenStore {
  private def key(purpose: TokenPurpose, rawToken: String): String =
    s"auth:token:${SingleUseTokenStore.storeKey(purpose, rawToken)}"
}
