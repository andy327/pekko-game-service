package com.andy327.actor.core

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.GameType

class TurnTimeoutConfigSpec extends AnyWordSpecLike with Matchers {

  "TurnTimeoutConfig.fromConfig" should {
    "parse per-game-type durations keyed by GameType.fromString names" in {
      val config = ConfigFactory.parseString("""
        pekko-game-service.turn-timeouts {
          texasholdem = 45s
          pig = 2m
        }
      """)

      val timeouts = TurnTimeoutConfig.fromConfig(config)

      timeouts.forGameType(GameType.TexasHoldEm) shouldBe Some(45.seconds)
      timeouts.forGameType(GameType.Pig) shouldBe Some(2.minutes)
    }

    "leave a game type with no entry without a timeout" in {
      val config = ConfigFactory.parseString("""
        pekko-game-service.turn-timeouts {
          texasholdem = 30s
        }
      """)

      TurnTimeoutConfig.fromConfig(config).forGameType(GameType.TicTacToe) shouldBe None
    }

    "yield an empty config when the stanza is absent" in {
      val timeouts = TurnTimeoutConfig.fromConfig(ConfigFactory.parseString("pekko-game-service {}"))

      timeouts.perGameType shouldBe empty
      timeouts.forGameType(GameType.TexasHoldEm) shouldBe None
    }

    "ignore an unrecognized game-type key rather than failing" in {
      val config = ConfigFactory.parseString("""
        pekko-game-service.turn-timeouts {
          not-a-game = 10s
          texasholdem = 45s
        }
      """)

      val timeouts = TurnTimeoutConfig.fromConfig(config)

      timeouts.perGameType.keySet shouldBe Set(GameType.TexasHoldEm)
    }
  }

  "TurnTimeoutConfig.default" should {
    "ship no turn clocks by default, so every game keeps the original wait-forever behavior until opted in" in {
      TurnTimeoutConfig.default.perGameType shouldBe empty
    }
  }
}
