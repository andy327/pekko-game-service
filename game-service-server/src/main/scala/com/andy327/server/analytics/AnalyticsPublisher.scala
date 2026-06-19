package com.andy327.server.analytics

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.syntax._

/** Emit seam for analytics events. The actor layer publishes [[GameAnalyticsEvent]]s through this without knowing how
  * (or whether) they are consumed; the consumer ([[AnalyticsConsumer]]) lives entirely on the other side of the seam.
  *
  * Publishing is fire-and-forget: callers are never blocked and delivery failures are silently discarded — analytics
  * must never affect gameplay.
  */
trait AnalyticsPublisher {

  /** Publish a single analytics event. */
  def publish(event: GameAnalyticsEvent): Unit
}

/** No-op publisher used in tests and single-instance setups that do not run the analytics pipeline. */
object NoOpAnalyticsPublisher extends AnalyticsPublisher {
  override def publish(event: GameAnalyticsEvent): Unit = ()
}

object RedisAnalyticsPublisher {

  /** The single Redis channel all analytics events are published to. */
  val Channel: String = "game-analytics"
}

/** Redis-backed publisher. Serializes each event to JSON and publishes it to the [[RedisAnalyticsPublisher.Channel]]
  * channel asynchronously.
  *
  * @param doPublish function that fires a publish command: `(channel, message) => IO[Unit]`; provided by
  *                  [[com.andy327.server.pubsub.RedisPubSubResource]] to avoid carrying the redis4cats streaming type
  *                  parameter
  */
class RedisAnalyticsPublisher(doPublish: (String, String) => IO[Unit])(implicit runtime: IORuntime)
    extends AnalyticsPublisher {

  override def publish(event: GameAnalyticsEvent): Unit = {
    val json = event.asJson.deepDropNullValues.noSpaces
    doPublish(RedisAnalyticsPublisher.Channel, json).unsafeRunAndForget()
  }
}
