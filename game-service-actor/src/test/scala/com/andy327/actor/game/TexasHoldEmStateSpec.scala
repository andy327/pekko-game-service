package com.andy327.actor.game

import java.util.UUID

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.PlayerId
import com.andy327.model.holdem.{Card, HandResult, PotAward, Street, TexasHoldEm}

class TexasHoldEmStateSpec extends AnyWordSpec with Matchers with OptionValues {
  private val alice: PlayerId = UUID.randomUUID() // seat 0
  private val bob: PlayerId = UUID.randomUUID() // seat 1
  private val carol: PlayerId = UUID.randomUUID() // seat 2

  private def cards(text: String*): List[Card] = text.map(Card(_)).toList

  private def game: TexasHoldEm =
    TexasHoldEm(
      playerIds = Vector(alice, bob, carol),
      stacks = Vector(900, 800, 1000),
      button = 0,
      holeCards = Vector(cards("AS", "AH"), cards("KS", "KH"), cards("QS", "QH")),
      board = cards("2C", "7D", "9S", "JD", "4H"),
      deck = Nil,
      street = Street.Flop, // only the first three community cards are revealed
      toAct = 1,
      streetContrib = Vector(0, 50, 0),
      committed = Vector(100, 150, 100),
      currentBet = 50,
      lastRaiseSize = 50,
      hasActed = Vector(true, true, false),
      folded = Vector(false, false, false),
      allIn = Vector(false, false, false),
      handResult = None,
      winner = None,
      moveCount = 5
    )

  "HoldEmState.of for a seated player" should {
    "reveal only that player's own hole cards, plus every seat's public state" in {
      val p1 = HoldEmState.of(game, Some(0))
      p1.viewerSeat shouldBe Some("P1")
      p1.holeCards shouldBe Some(List("AS", "AH"))
      p1.seats shouldBe List(
        HoldEmSeat("P1", 900, 100, 0, folded = false, allIn = false),
        HoldEmSeat("P2", 800, 150, 50, folded = false, allIn = false),
        HoldEmSeat("P3", 1000, 100, 0, folded = false, allIn = false)
      )

      val p2 = HoldEmState.of(game, Some(1))
      p2.holeCards shouldBe Some(List("KS", "KH")) // its own hand, not seat 0's
    }

    "reveal only the community cards dealt on the current street" in {
      HoldEmState.of(game, Some(0)).board shouldBe List("2C", "7D", "9S") // the flop, not the turn or river
      HoldEmState.of(game.copy(street = Street.PreFlop), Some(0)).board shouldBe Nil
      HoldEmState.of(game.copy(street = Street.River), Some(0)).board shouldBe
        List("2C", "7D", "9S", "JD", "4H")
    }

    "expose the betting state and each player's amount to call" in {
      val p1 = HoldEmState.of(game, Some(0))
      p1.button shouldBe "P1"
      p1.currentPlayer shouldBe "P2"
      p1.currentBet shouldBe 50
      p1.minRaise shouldBe 100 // current bet plus the last raise size
      p1.pot shouldBe 350
      p1.toCall shouldBe 50 // seat 0 has nothing in this street yet

      HoldEmState.of(game, Some(1)).toCall shouldBe 0 // seat 1 has already matched the bet
    }

    "report the minimum raise as the big blind when there is no bet yet on the street" in {
      val noBet = game.copy(currentBet = 0, streetContrib = Vector(0, 0, 0))
      HoldEmState.of(noBet, Some(0)).minRaise shouldBe TexasHoldEm.BigBlind
    }
  }

  "HoldEmState.of for a spectator" should {
    "hide every hole card while still reporting the public state" in {
      val view = HoldEmState.of(game, None)
      view.viewerSeat shouldBe None
      view.holeCards shouldBe None
      view.toCall shouldBe 0
      view.seats.map(_.stack) shouldBe List(900, 800, 1000)
    }
  }

  "HoldEmState.of with a finished hand" should {
    "expose the showdown reveal — hole cards of the seats that reached it" in {
      val result = HandResult(
        board = cards("2C", "7D", "9S", "JD", "4H"),
        shownHands = Map(0 -> cards("AS", "AH"), 2 -> cards("QS", "QH")),
        awards = List(PotAward(350, List(0), Some("one pair, As")))
      )
      val ended = game.copy(handResult = Some(result), winner = Some(0), street = Street.River)
      val shown = HoldEmState.of(ended, Some(1)).handResult.value

      shown.board shouldBe List("2C", "7D", "9S", "JD", "4H")
      shown.shownHands shouldBe Map("P1" -> List("AS", "AH"), "P3" -> List("QS", "QH"))
      shown.awards shouldBe List(HoldEmPotAward(350, List("P1"), Some("one pair, As")))
      HoldEmState.of(ended, Some(1)).winner shouldBe Some("P1")
    }
  }
}
