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
  * Redemption reads and deletes the key in one atomic `GETDEL` for single-use safety: Redis runs the command
  * atomically, so if two requests race to redeem the same token, exactly one `GETDEL` returns the value (the winner)
  * and the rest see the key already gone and are rejected. A token can therefore never be redeemed twice, even
  * concurrently.
  *
  * @param redis Redis commands handle used for all reads and writes
  */
class RedisSingleUseTokenStore(redis: RedisCommands[IO, String, String]) extends SingleUseTokenStore {
  import RedisSingleUseTokenStore.key

  override def issue(purpose: TokenPurpose, accountId: UUID, ttl: FiniteDuration): IO[String] =
    IO(SingleUseTokenStore.generateToken()).flatTap(raw => redis.setEx(key(purpose, raw), accountId.toString, ttl))

  override def consume(purpose: TokenPurpose, rawToken: String): IO[Option[UUID]] =
    // GETDEL fetches and removes the key atomically, so only one racer ever sees the value.
    redis.getDel(key(purpose, rawToken)).map(_.flatMap(value => Try(UUID.fromString(value)).toOption))
}

object RedisSingleUseTokenStore {
  private def key(purpose: TokenPurpose, rawToken: String): String =
    s"auth:token:${SingleUseTokenStore.storeKey(purpose, rawToken)}"
}
