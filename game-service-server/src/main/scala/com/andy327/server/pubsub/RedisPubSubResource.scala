package com.andy327.server.pubsub

import org.slf4j.LoggerFactory

import com.typesafe.config.Config

import cats.effect.IO
import cats.effect.kernel.Resource

import dev.profunktor.redis4cats.connection.RedisClient
import dev.profunktor.redis4cats.data.{RedisChannel, RedisCodec, RedisPattern}
import dev.profunktor.redis4cats.effect.Log
import dev.profunktor.redis4cats.pubsub.PubSub
import fs2.Stream

/** Factory for a managed Redis pub/sub connection that hides the redis4cats streaming type machinery.
  *
  * Uses a dedicated Lettuce pub/sub connection (separate from the one backing `RedisClientResource`) because Lettuce
  * pub/sub connections are stateful and cannot share a regular command connection.
  *
  * Concrete redis4cats streaming types are hidden behind plain Scala abstractions so callers
  * ([[RedisGameEventPublisher]], [[GameEventSubscriber]]) do not need to carry the higher-kinded `F` parameter that
  * redis4cats-streams uses internally.
  */
object RedisPubSubResource {

  implicit private val log: Log[IO] = new Log[IO] {
    private val logger = LoggerFactory.getLogger("RedisPubSubResource")
    override def debug(msg: => String): IO[Unit] = IO(logger.debug(msg))
    override def info(msg: => String): IO[Unit] = IO(logger.info(msg))
    override def error(msg: => String): IO[Unit] = IO(logger.error(msg))
  }

  /** Build a managed pub/sub resource from `config`.
    *
    * @param config application config; must contain `pekko-game-service.redis.uri`
    * @return a `Resource` that connects on acquire and disconnects on release, yielding:
    *         - `publishFn(channel, message) => IO[Unit]` — publishes to a named channel
    *         - `subscribeStream: Stream[IO, (channel, message)]` — all `game-events:*` events
    */
  def apply(config: Config): Resource[IO, ((String, String) => IO[Unit], Stream[IO, (String, String)])] = {
    val uri = config.getString("pekko-game-service.redis.uri")
    RedisClient[IO].from(uri).flatMap { client =>
      // Let the type of `pubSub` be fully inferred to avoid writing the higher-kinded F type parameter.
      // redis4cats-streams uses F = [β]Stream[IO, β] internally; the resulting method calls produce
      // plain Stream[IO, *] and IO[Unit] values that are straightforward to work with.
      PubSub.mkPubSubConnection[IO, String, String](client, RedisCodec.Utf8).map { pubSub =>
        val publishFn: (String, String) => IO[Unit] =
          (channel, msg) => Stream.emit(msg).covary[IO].through(pubSub.publish(RedisChannel(channel))).compile.drain

        val subscribeStream: Stream[IO, (String, String)] =
          pubSub.psubscribe(RedisPattern("game-events:*")).map(e => (e.channel, e.data))

        (publishFn, subscribeStream)
      }
    }
  }
}
