package com.andy327.server.analytics

import org.slf4j.LoggerFactory

import cats.effect.IO

import fs2.Stream
import io.circe.parser.decode

import com.andy327.server.analytics.GameAnalyticsCodecs.decoder

/** Consumes analytics events off the `game-analytics` channel and folds each one into [[GameMetrics]].
  *
  * This is the read side of the analytics emit seam. It is fully decoupled from the actor layer: events arrive as JSON
  * strings (from Redis pub/sub via [[com.andy327.server.pubsub.RedisPubSubResource]] in production, or any stream in
  * tests), are decoded to [[GameAnalyticsEvent]], and recorded. A message that fails to decode is logged and skipped so
  * one malformed event cannot tear down the pipeline.
  *
  * @param eventStream stream of raw JSON event payloads from the analytics channel
  * @param metrics the metrics each decoded event is recorded into
  */
class AnalyticsConsumer(eventStream: Stream[IO, String], metrics: GameMetrics) {
  private val logger = LoggerFactory.getLogger(getClass)

  /** Process incoming events until cancelled — start as a background fiber before the HTTP server accepts connections.
    * The stream terminates naturally if the Redis connection is lost.
    */
  def run: IO[Unit] =
    eventStream
      .evalMap { json =>
        decode[GameAnalyticsEvent](json) match {
          case Right(event) => IO(metrics.record(event))
          case Left(err)    => IO(logger.warn(s"Failed to decode analytics event: $err"))
        }
      }
      .compile
      .drain
}
