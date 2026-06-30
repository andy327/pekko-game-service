package com.andy327.actor.tracing

import scala.jdk.CollectionConverters._

import com.typesafe.config.{Config, ConfigFactory}

/** Controls whether actor message tracing is active and how aggressively it samples.
  *
  * Loaded from the `pekko-game-service.tracing` stanza in `application.conf` (or the reference defaults). When
  * `enabled` is `false` no [[TraceEvent]] objects are allocated and no `TracingInterceptor` is installed, so there is
  * no per-message overhead in production.
  *
  * @param enabled master switch; must be `true` for any tracing to occur
  * @param sampleRate default fraction of messages to record, in [0.0, 1.0]; 1.0 records every message, lower values
  *                   reduce overhead for high-frequency actors (e.g. a bot sending moves rapidly). Applies to any
  *                   message type with no entry in `messageSampleRates`
  * @param bufferSize number of most-recent [[TraceEvent]]s retained by [[TraceCollector]]'s in-memory buffer
  * @param messageSampleRates per-message-type overrides of `sampleRate`, keyed by simple class name (e.g.
  *                           `"MakeMove"`). Lets a specific high-frequency message type be sampled down (or up)
  *                           without changing the rate applied to every other message type
  */
final case class TracingConfig(
    enabled: Boolean,
    sampleRate: Double,
    bufferSize: Int,
    messageSampleRates: Map[String, Double] = Map.empty
) {

  /** The sample rate to apply to a message named `messageType`: its entry in [[messageSampleRates]] if present,
    * otherwise the default [[sampleRate]].
    */
  def sampleRateFor(messageType: String): Double = messageSampleRates.getOrElse(messageType, sampleRate)
}

object TracingConfig {
  private val Namespace = "pekko-game-service.tracing"

  def fromConfig(config: Config): TracingConfig = {
    val overridesPath = s"$Namespace.message-sample-rates"
    val messageSampleRates =
      if (config.hasPath(overridesPath))
        config
          .getConfig(overridesPath)
          .entrySet()
          .asScala
          .map(entry => entry.getKey -> entry.getValue.unwrapped().asInstanceOf[Number].doubleValue())
          .toMap
      else Map.empty[String, Double]

    TracingConfig(
      enabled = config.getBoolean(s"$Namespace.enabled"),
      sampleRate = config.getDouble(s"$Namespace.sample-rate"),
      bufferSize = config.getInt(s"$Namespace.buffer-size"),
      messageSampleRates = messageSampleRates
    )
  }

  /** Loads from the standard `ConfigFactory.load()` resolution chain (application.conf → reference.conf). */
  lazy val default: TracingConfig = fromConfig(ConfigFactory.load())
}
