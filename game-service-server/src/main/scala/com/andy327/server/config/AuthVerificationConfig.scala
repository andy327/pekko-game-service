package com.andy327.server.config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import com.typesafe.config.{Config, ConfigFactory}

/** Email-verification policy, read from the `auth.verification` stanza.
  *
  * @param tokenTtl how long an issued verification token stays valid; after it, the token has self-expired from the
  *   store and can no longer verify the address
  */
final case class AuthVerificationConfig(tokenTtl: FiniteDuration)

object AuthVerificationConfig {
  private val Namespace = "auth.verification"

  def fromConfig(config: Config = ConfigFactory.load()): AuthVerificationConfig =
    AuthVerificationConfig(tokenTtl = config.getConfig(Namespace).getDuration("token-ttl").toMillis.millis)
}
