package com.andy327.actor.game

import java.util.UUID

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.PlayerId
import com.andy327.model.liarsdice.{Bid, LiarsDice, Reveal, StandingBid}

class LiarsDiceStateSpec extends AnyWordSpec with Matchers with OptionValues {
  val alice: PlayerId = UUID.randomUUID() // seat 0
  val bob: PlayerId = UUID.randomUUID() // seat 1

  private def game: LiarsDice =
    LiarsDice(
      playerIds = Vector(alice, bob),
      dice = Vector(Vector(2, 3, 4), Vector(5, 6, 1)),
      currentSeat = 0,
      standing = Some(StandingBid(Bid(2, Some(4)), 1)),
      lastReveal = None,
      winner = None,
      moveCount = 3
    )

  "LiarsDiceState.of for a seated player" should {
    "reveal only that player's own dice, plus every seat's count" in {
      val p1 = LiarsDiceState.of(game, Some(0))
      p1.viewerSeat shouldBe Some("P1")
      p1.dice shouldBe Some(List(2, 3, 4))
      p1.diceCounts shouldBe Map("P1" -> 3, "P2" -> 3)

      val p2 = LiarsDiceState.of(game, Some(1))
      p2.dice shouldBe Some(List(5, 6, 1)) // its own hand, not seat 0's
    }

    "expose the standing bid and whose turn it is" in {
      val view = LiarsDiceState.of(game, Some(0))
      view.currentBid shouldBe Some(BidView(2, Some(4)))
      view.currentPlayer shouldBe "P1"
      view.winner shouldBe None
    }
  }

  "LiarsDiceState.of for a spectator" should {
    "hide every hand while still reporting the counts" in {
      val view = LiarsDiceState.of(game, None)
      view.viewerSeat shouldBe None
      view.dice shouldBe None
      view.diceCounts shouldBe Map("P1" -> 3, "P2" -> 3)
    }
  }

  "LiarsDiceState.of with a resolved challenge" should {
    "expose every seat's dice in the reveal — the one public moment" in {
      val reveal = Reveal(
        bid = Bid(3, Some(4)),
        count = 5,
        allDice = Vector(Vector(4, 4, 4), Vector(4, 1)),
        challengerSeat = 1,
        bidderSeat = 0,
        loserSeat = 1,
        diceLost = 2
      )
      // the challenger (seat 1) lost both dice, so their current hand is empty, but the reveal still shows what it held
      val ended = game.copy(dice = Vector(Vector(4, 4, 4), Vector.empty), lastReveal = Some(reveal), winner = Some(0))
      val view = LiarsDiceState.of(ended, Some(1))
      val shown = view.lastReveal.value
      shown.bid shouldBe BidView(3, Some(4))
      shown.count shouldBe 5
      shown.dice shouldBe Map("P1" -> List(4, 4, 4), "P2" -> List(4, 1))
      shown.challenger shouldBe "P2"
      shown.bidder shouldBe "P1"
      shown.loser shouldBe "P2"
      shown.diceLost shouldBe 2
      view.winner shouldBe Some("P1")
      view.diceCounts shouldBe Map("P1" -> 3, "P2" -> 0)
    }
  }

  "LiarsDiceState.of for a wild ones bid" should {
    "represent a faceless bid with no face" in {
      val onesGame = game.copy(standing = Some(StandingBid(Bid(2, None), 1)))
      LiarsDiceState.of(onesGame, Some(0)).currentBid shouldBe Some(BidView(2, None))
    }
  }
}
