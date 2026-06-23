package com.andy327.server.config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import com.typesafe.config.ConfigFactory

object JwtConfig {
  private val config = ConfigFactory.load()

  /** The shared secret key used for signing and verifying JWT tokens. Must be defined in `application.conf` under
    * `jwt.secret`, for example:
    *
    * jwt { secret = "your-secret-key" }
    */
  val secretKey: String = config.getString("jwt.secret")

  /** How long an issued token stays valid (its `exp` past `iat`), read from `jwt.ttl`. */
  val ttl: FiniteDuration = config.getDuration("jwt.ttl").toMillis.millis
}
