package com.andy327.actor.tracing

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TracingConfigSpec extends AnyWordSpecLike with Matchers {

  "TracingConfig.fromConfig" should {
    "parse enabled, sample-rate, and buffer-size" in {
      val config = ConfigFactory.parseString("""
        pekko-game-service.tracing {
          enabled = true
          sample-rate = 0.5
          buffer-size = 250
        }
      """)

      val tracingConfig = TracingConfig.fromConfig(config)

      tracingConfig.enabled shouldBe true
      tracingConfig.sampleRate shouldBe 0.5
      tracingConfig.bufferSize shouldBe 250
    }

    "default messageSampleRates to empty when message-sample-rates is absent" in {
      val config = ConfigFactory.parseString("""
        pekko-game-service.tracing {
          enabled = false
          sample-rate = 1.0
          buffer-size = 1000
        }
      """)

      TracingConfig.fromConfig(config).messageSampleRates shouldBe Map.empty
    }

    "parse message-sample-rates into a Map[String, Double]" in {
      val config = ConfigFactory.parseString("""
        pekko-game-service.tracing {
          enabled = true
          sample-rate = 1.0
          buffer-size = 1000
          message-sample-rates {
            "MakeMove" = 0.1
            "Subscribe" = 0.25
          }
        }
      """)

      TracingConfig.fromConfig(config).messageSampleRates shouldBe Map("MakeMove" -> 0.1, "Subscribe" -> 0.25)
    }
  }

  "TracingConfig.sampleRateFor" should {
    "return the override for a message type present in messageSampleRates" in {
      val config = TracingConfig(enabled = true, sampleRate = 1.0, bufferSize = 1000, Map("MakeMove" -> 0.1))

      config.sampleRateFor("MakeMove") shouldBe 0.1
    }

    "fall back to sampleRate for a message type with no override" in {
      val config = TracingConfig(enabled = true, sampleRate = 0.7, bufferSize = 1000, Map("MakeMove" -> 0.1))

      config.sampleRateFor("Subscribe") shouldBe 0.7
    }
  }
}
