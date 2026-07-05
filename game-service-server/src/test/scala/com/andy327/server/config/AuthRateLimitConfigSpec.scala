package com.andy327.server.config

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AuthRateLimitConfigSpec extends AnyWordSpec with Matchers {

  "AuthRateLimitConfig.fromConfig" should {
    "read every field from the auth.rate-limit stanza" in {
      val config = ConfigFactory.parseString("""
        auth.rate-limit {
          enabled = true
          ip { window = 10m, max-attempts = 30 }
          lockout { threshold = 4, window = 5m, duration = 20m }
        }
      """)

      AuthRateLimitConfig.fromConfig(config) shouldBe AuthRateLimitConfig(
        enabled = true,
        ipWindow = 10.minutes,
        ipMaxAttempts = 30,
        lockoutThreshold = 4,
        lockoutWindow = 5.minutes,
        lockoutDuration = 20.minutes
      )
    }

    "load the packaged application.conf defaults" in {
      val config = AuthRateLimitConfig.fromConfig()
      config.enabled shouldBe true
      config.ipMaxAttempts should be > 0
      config.lockoutThreshold should be > 0
      config.lockoutDuration should be > 0.seconds
    }
  }
}
