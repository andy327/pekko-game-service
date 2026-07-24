package com.andy327.actor.bot

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BotConfigSpec extends AnyWordSpec with Matchers {

  "BotConfig.fromConfig" should {
    "read a configured think delay" in {
      val config = ConfigFactory.parseString("pekko-game-service.bot.think-delay = 250ms")
      BotConfig.fromConfig(config).thinkDelay shouldBe 250.millis
    }

    "read a zero think delay" in {
      val config = ConfigFactory.parseString("pekko-game-service.bot.think-delay = 0ms")
      BotConfig.fromConfig(config).thinkDelay shouldBe Duration.Zero
    }

    "fall back to the default when the setting is absent" in {
      BotConfig.fromConfig(ConfigFactory.empty()).thinkDelay shouldBe BotActor.DefaultThinkDelay
    }
  }
}
