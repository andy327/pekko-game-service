package com.andy327.actor.lobby

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class BotIdSpec extends AnyWordSpec with Matchers {

  "BotId" should {
    "recognize every id it mints and no registered account's id" in {
      BotId.isBot(BotId.forOrdinal(0)) shouldBe true
      BotId.isBot(BotId.forOrdinal(41)) shouldBe true
      // registered accounts carry random (version 4) UUIDs, whose version nibble a bot id never matches
      BotId.isBot(UUID.randomUUID()) shouldBe false
    }

    "mint deterministic ids, so the same ordinal always names the same bot" in {
      BotId.forOrdinal(2) shouldBe BotId.forOrdinal(2)
      BotId.forOrdinal(2) should not be BotId.forOrdinal(3)
    }

    "display bots by one-based ordinal" in {
      BotId.player(0).name shouldBe "Bot 1"
      BotId.player(1).name shouldBe "Bot 2"
      BotId.player(0).id shouldBe BotId.forOrdinal(0)
    }

    "hand out the lowest free ordinal, skipping seated bots but not humans" in {
      BotId.nextFor(Set.empty).name shouldBe "Bot 1"
      BotId.nextFor(Set(BotId.forOrdinal(0))).name shouldBe "Bot 2"
      BotId.nextFor(Set(BotId.forOrdinal(0), BotId.forOrdinal(1))).name shouldBe "Bot 3"
      // a freed ordinal is reused, and human ids in the set never block one
      BotId.nextFor(Set(BotId.forOrdinal(1), UUID.randomUUID())).name shouldBe "Bot 1"
    }
  }
}
