package com.andy327.actor.bot

import scala.concurrent.duration.FiniteDuration
import scala.jdk.DurationConverters._

import com.typesafe.config.{Config, ConfigFactory}

/** Runtime tuning for bot play.
  *
  * Loaded from the `pekko-game-service.bot` stanza in `application.conf` (or the reference defaults). Absent settings
  * fall back to the code defaults, so the feature needs no configuration to run.
  *
  * @param thinkDelay the pause a bot takes between recognizing its turn and submitting; a shorter value speeds a
  *                   bot-versus-bot game (and load tests), zero removes the pause entirely
  */
final case class BotConfig(thinkDelay: FiniteDuration)

object BotConfig {
  private val ThinkDelayPath = "pekko-game-service.bot.think-delay"

  /** Reads the `bot` stanza; an absent `think-delay` keeps [[BotActor.DefaultThinkDelay]]. */
  def fromConfig(config: Config): BotConfig =
    if (config.hasPath(ThinkDelayPath)) BotConfig(config.getDuration(ThinkDelayPath).toScala)
    else BotConfig(BotActor.DefaultThinkDelay)

  /** Loads from the standard `ConfigFactory.load()` resolution chain (application.conf → reference.conf). */
  lazy val default: BotConfig = fromConfig(ConfigFactory.load())
}
