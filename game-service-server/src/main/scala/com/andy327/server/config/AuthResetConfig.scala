package com.andy327.server.config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import com.typesafe.config.{Config, ConfigFactory}

/** Password-reset policy, read from the `auth.reset` stanza.
  *
  * @param tokenTtl how long an issued reset token stays valid; after it, the token has self-expired from the store and
  *   every token predating it is unusable
  */
final case class AuthResetConfig(tokenTtl: FiniteDuration)

object AuthResetConfig {
  private val Namespace = "auth.reset"

  def fromConfig(config: Config = ConfigFactory.load()): AuthResetConfig =
    AuthResetConfig(tokenTtl = config.getConfig(Namespace).getDuration("token-ttl").toMillis.millis)
}
