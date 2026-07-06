package com.andy327.server.config

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import com.typesafe.config.{Config, ConfigFactory}

/** Throttle and lockout policy for the auth endpoints, read from the `auth.rate-limit` stanza.
  *
  * @param enabled master switch; when `false` the server wires a no-op limiter and none of the values below apply
  * @param ipWindow fixed window over which per-IP attempts are counted
  * @param ipMaxAttempts attempts admitted per IP per `ipWindow` before further requests are throttled (429)
  * @param lockoutThreshold consecutive failed logins (per email) that trigger a lockout
  * @param lockoutWindow rolling window within which failures must accrue to reach the threshold
  * @param lockoutDuration how long an account stays locked once the threshold is reached
  */
final case class AuthRateLimitConfig(
    enabled: Boolean,
    ipWindow: FiniteDuration,
    ipMaxAttempts: Int,
    lockoutThreshold: Int,
    lockoutWindow: FiniteDuration,
    lockoutDuration: FiniteDuration
)

object AuthRateLimitConfig {
  private val Namespace = "auth.rate-limit"

  def fromConfig(config: Config = ConfigFactory.load()): AuthRateLimitConfig = {
    val rl = config.getConfig(Namespace)
    AuthRateLimitConfig(
      enabled = rl.getBoolean("enabled"),
      ipWindow = rl.getDuration("ip.window").toMillis.millis,
      ipMaxAttempts = rl.getInt("ip.max-attempts"),
      lockoutThreshold = rl.getInt("lockout.threshold"),
      lockoutWindow = rl.getDuration("lockout.window").toMillis.millis,
      lockoutDuration = rl.getDuration("lockout.duration").toMillis.millis
    )
  }
}
