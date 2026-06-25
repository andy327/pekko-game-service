package com.andy327.server.analytics

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.syntax._

import com.andy327.actor.events.{AnalyticsPublisher, GameAnalyticsEvent}
import com.andy327.server.analytics.GameAnalyticsCodecs.encoder

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
