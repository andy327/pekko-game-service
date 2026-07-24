package com.andy327.actor.bot

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BotDifficultySpec extends AnyWordSpec with Matchers {

  "BotDifficulty" should {
    "resolve every advertised level from its own label, case-insensitively" in {
      BotDifficulty.all.foreach(level => BotDifficulty.fromString(level.label) shouldBe Some(level))
      BotDifficulty.fromString("STANDARD") shouldBe Some(BotDifficulty.Standard)
    }

    "reject a label it does not know" in {
      BotDifficulty.fromString("godlike") shouldBe None
    }

    "advertise its default among the levels a caller may ask for" in {
      BotDifficulty.all should contain(BotDifficulty.Default)
    }

    "give every level a distinct label" in {
      BotDifficulty.all.map(_.label).distinct should have size BotDifficulty.all.size.toLong
    }
  }
}
