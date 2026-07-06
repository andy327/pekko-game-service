package com.andy327.actor.core

import scala.concurrent.duration.FiniteDuration
import scala.jdk.CollectionConverters._
import scala.jdk.DurationConverters._

import com.typesafe.config.{Config, ConfigFactory}

import com.andy327.model.core.GameType

/** Per-game-type turn clock: how long a seated player may take before the shared [[TurnBasedGameActor]] applies that
  * game's fallback action (a safe auto-move or a forfeit).
  *
  * Loaded from the `pekko-game-service.turn-timeouts` stanza in `application.conf` (or the reference defaults). Each
  * key is a game-type name as accepted by `GameType.fromString` (e.g. `texasholdem`), and its value is a HOCON duration
  * (e.g. `45s`). A game type with no entry has no turn clock — the actor never arms a timer for it, preserving the
  * original wait-forever behavior — so the feature is opt-in per game.
  *
  * @param perGameType configured turn clock for each game type that has one; absent types map to no timeout
  */
final case class TurnTimeoutConfig(perGameType: Map[GameType, FiniteDuration]) {

  /** The turn clock configured for `gameType`, or `None` if it has no timeout (the actor arms no timer). */
  def forGameType(gameType: GameType): Option[FiniteDuration] = perGameType.get(gameType)
}

object TurnTimeoutConfig {
  private val Namespace = "pekko-game-service.turn-timeouts"

  /** Parse the `turn-timeouts` stanza. Keys are resolved back to a `GameType` via `GameType.fromString`; an
    * unrecognized key (e.g. a typo) is ignored rather than failing, which means it simply has no effect. An absent
    * stanza yields an empty config (no timeouts anywhere).
    */
  def fromConfig(config: Config): TurnTimeoutConfig =
    if (!config.hasPath(Namespace)) TurnTimeoutConfig(Map.empty)
    else {
      val stanza = config.getConfig(Namespace)
      val perGameType = stanza
        .root()
        .keySet()
        .asScala
        .flatMap(key => GameType.fromString(key).map(_ -> stanza.getDuration(key).toScala))
        .toMap
      TurnTimeoutConfig(perGameType)
    }

  /** Loads from the standard `ConfigFactory.load()` resolution chain (application.conf → reference.conf). */
  lazy val default: TurnTimeoutConfig = fromConfig(ConfigFactory.load())
}
