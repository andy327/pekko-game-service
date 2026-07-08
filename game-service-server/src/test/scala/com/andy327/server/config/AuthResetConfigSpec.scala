package com.andy327.server.config

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AuthResetConfigSpec extends AnyWordSpec with Matchers {

  "AuthResetConfig.fromConfig" should {
    "read the token TTL from the auth.reset stanza" in {
      val config = ConfigFactory.parseString("auth.reset.token-ttl = 30m")
      AuthResetConfig.fromConfig(config) shouldBe AuthResetConfig(tokenTtl = 30.minutes)
    }

    "load the packaged application.conf default" in {
      AuthResetConfig.fromConfig().tokenTtl should be > 0.seconds
    }
  }
}
