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
      p1.viewerSeat shouldBe Some(0)
      p1.holeCards shouldBe Some(cards("AS", "AH"))
      p1.seats shouldBe List(
        HoldEmSeat(900, 100, 0, folded = false, allIn = false),
        HoldEmSeat(800, 150, 50, folded = false, allIn = false),
        HoldEmSeat(1000, 100, 0, folded = false, allIn = false)
      )

      val p2 = HoldEmState.of(game, Some(1))
      p2.holeCards shouldBe Some(cards("KS", "KH")) // its own hand, not seat 0's
    }

    "reveal only the community cards dealt on the current street" in {
      HoldEmState.of(game, Some(0)).board shouldBe cards("2C", "7D", "9S") // the flop, not the turn or river
      HoldEmState.of(game.copy(street = Street.PreFlop), Some(0)).board shouldBe Nil
      HoldEmState.of(game.copy(street = Street.River), Some(0)).board shouldBe
        cards("2C", "7D", "9S", "JD", "4H")
    }

    "expose the betting state and each player's amount to call" in {
      val p1 = HoldEmState.of(game, Some(0))
      p1.button shouldBe 0
      p1.currentPlayer shouldBe 1
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
      val view = HoldEmState.of(ended, Some(1))
      view.handResult.value shouldBe result // the model's public reveal travels as-is
      view.winner shouldBe Some(0)
    }
  }

  "HoldEmState.of legal moves" should {
    "offer the player to act their chip-free actions and sizing range, and nobody else anything" in {
      // seat 1 has matched the bet, so it may check or fold, and raise anywhere from a min-raise to its all-in
      val toAct = HoldEmState.of(game, Some(1))
      toAct.legalMoves shouldBe List(
        MovePayload.HoldEmAction("fold", None),
        MovePayload.HoldEmAction("check", None)
      )
      toAct.betSizing shouldBe Some(BetSizing("raise", min = 100, max = 850)) // 50+50 up to 50+800 all-in

      val waiting = HoldEmState.of(game, Some(0))
      waiting.legalMoves shouldBe empty
      waiting.betSizing shouldBe None

      HoldEmState.of(game, None).legalMoves shouldBe empty // spectator
      HoldEmState.of(game, None).betSizing shouldBe None
    }

    "offer call rather than check when chips are owed, and name the sizing a bet when nothing stands" in {
      val noBet = game.copy(currentBet = 0, streetContrib = Vector(0, 0, 0))
      val opening = HoldEmState.of(noBet, Some(1))
      opening.legalMoves should contain(MovePayload.HoldEmAction("check", None))
      opening.betSizing shouldBe Some(BetSizing("bet", min = TexasHoldEm.BigBlind, max = 800))

      val owing = game.copy(streetContrib = Vector(0, 0, 0)) // the 50 bet stands and seat 1 has nothing in
      val view = HoldEmState.of(owing, Some(1))
      view.legalMoves shouldBe List(
        MovePayload.HoldEmAction("fold", None),
        MovePayload.HoldEmAction("call", None)
      )
    }

    "collapse the sizing to the all-in for a short stack, dropping it when the bet cannot be exceeded at all" in {
      val shortRaise = game.copy(stacks = Vector(900, 60, 1000), streetContrib = Vector(0, 0, 0))
      HoldEmState.of(shortRaise, Some(1)).betSizing shouldBe Some(BetSizing("raise", min = 60, max = 60))

      val tooShort = game.copy(stacks = Vector(900, 30, 1000), streetContrib = Vector(0, 0, 0))
      val view = HoldEmState.of(tooShort, Some(1))
      view.betSizing shouldBe None
      view.legalMoves should contain(MovePayload.HoldEmAction("call", None)) // the short all-in call remains
    }
  }
}
