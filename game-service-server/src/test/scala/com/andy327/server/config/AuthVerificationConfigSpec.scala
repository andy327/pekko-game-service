package com.andy327.server.config

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AuthVerificationConfigSpec extends AnyWordSpec with Matchers {

  "AuthVerificationConfig.fromConfig" should {
    "read the token TTL from the auth.verification stanza" in {
      val config = ConfigFactory.parseString("auth.verification.token-ttl = 12h")
      AuthVerificationConfig.fromConfig(config) shouldBe AuthVerificationConfig(tokenTtl = 12.hours)
    }

    "load the packaged application.conf default" in {
      AuthVerificationConfig.fromConfig().tokenTtl should be > 0.seconds
    }
  }
}
