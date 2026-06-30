package com.andy327.actor.tracing

import com.typesafe.config.{Config, ConfigFactory}

/** Controls whether actor message tracing is active and how aggressively it samples.
  *
  * Loaded from the `pekko-game-service.tracing` stanza in `application.conf` (or the reference defaults). When
  * `enabled` is `false` no [[TraceEvent]] objects are allocated and no `TracingInterceptor` is installed, so there is
  * no per-message overhead in production.
  *
  * @param enabled master switch; must be `true` for any tracing to occur
  * @param sampleRate fraction of messages to record, in [0.0, 1.0]; 1.0 records every message, lower values reduce
  *                   overhead for high-frequency actors (e.g. a bot sending moves rapidly)
  * @param bufferSize number of most-recent [[TraceEvent]]s retained by [[TraceCollector]]'s in-memory buffer
  */
final case class TracingConfig(enabled: Boolean, sampleRate: Double, bufferSize: Int)

object TracingConfig {
  private val Namespace = "pekko-game-service.tracing"

  def fromConfig(config: Config): TracingConfig =
    TracingConfig(
      enabled = config.getBoolean(s"$Namespace.enabled"),
      sampleRate = config.getDouble(s"$Namespace.sample-rate"),
      bufferSize = config.getInt(s"$Namespace.buffer-size")
    )

  /** Loads from the standard `ConfigFactory.load()` resolution chain (application.conf → reference.conf). */
  lazy val default: TracingConfig = fromConfig(ConfigFactory.load())
}
