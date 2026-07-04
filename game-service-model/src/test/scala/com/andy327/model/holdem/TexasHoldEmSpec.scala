package com.andy327.model.holdem

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.scalatest.{EitherValues, OptionValues}

import com.andy327.model.core.GameError.{GameOver, InvalidPlayer, InvalidTurn}
import com.andy327.model.core.{InProgress, PlayerId, Won}

class TexasHoldEmSpec extends AnyWordSpec with Matchers with OptionValues with EitherValues {
  import Action._

  private val alice: PlayerId = UUID.randomUUID()
  private val bob: PlayerId = UUID.randomUUID()
  private val carol: PlayerId = UUID.randomUUID()

  /** A full deck; used as each move's next-hand deck since only a hand-starting move ever consumes it. */
  private val full: List[Card] = Card.deck

  private def cards(text: String*): List[Card] = text.map(Card(_)).toList

  private def move(action: Action, deck: List[Card] = Nil): HoldEmMove = HoldEmMove(action, deck)

  /** A fresh heads-up game, seat 0 to act with the blinds posted. */
  private def headsUp: TexasHoldEm = TexasHoldEm.newGame(Seq(alice, bob), full)

  /** Applies a sequence of `(seat, action)` moves, each carrying a full next-hand deck, failing on any rejection. */
  private def playAll(game: TexasHoldEm, moves: (Int, Action)*): TexasHoldEm =
    moves.foldLeft(game) { case (g, (seat, action)) =>
      g.play(seat, move(action, full)) match {
        case Right(next) => next
        case Left(err)   => fail(s"move ($seat, $action) rejected: $err")
      }
    }

  /** Builds a mid-hand table directly, defaulting the bookkeeping so a test need only set what it exercises. */
  private def table(
      ids: Vector[PlayerId],
      stacks: Vector[Int],
      holeCards: Vector[List[Card]],
      board: List[Card],
      toAct: Int = 0,
      button: Int = 0,
      currentBet: Int = 0,
      street: Street = Street.PreFlop,
      committed: Option[Vector[Int]] = None,
      folded: Option[Vector[Boolean]] = None,
      deck: List[Card] = Nil
  ): TexasHoldEm = {
    val size = ids.size
    TexasHoldEm(
      playerIds = ids,
      stacks = stacks,
      button = button,
      holeCards = holeCards,
      board = board,
      deck = deck,
      street = street,
      toAct = toAct,
      streetContrib = Vector.fill(size)(0),
      committed = committed.getOrElse(Vector.fill(size)(0)),
      currentBet = currentBet,
      lastRaiseSize = TexasHoldEm.BigBlind,
      hasActed = Vector.fill(size)(false),
      folded = folded.getOrElse(Vector.fill(size)(false)),
      allIn = Vector.fill(size)(false),
      handResult = None,
      winner = None,
      moveCount = 0
    )
  }

  "A TexasHoldEm game" should {

    "resolve participants to their seat indices with playerFor" in {
      val g = headsUp
      g.playerFor(alice) shouldBe Some(0)
      g.playerFor(bob) shouldBe Some(1)
      g.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      headsUp.players shouldBe List(alice, bob)
    }

    "start with blinds posted, cards dealt, and the button to act" in {
      val g = headsUp
      g.stacks shouldBe Vector(995, 990) // SB 5, BB 10
      g.pot shouldBe 15
      g.currentBet shouldBe 10
      g.button shouldBe 0
      g.holeCards(0).size shouldBe 2
      g.holeCards(1).size shouldBe 2
      g.board.size shouldBe 5
      g.toAct shouldBe 0
      g.currentPlayer shouldBe 0
      g.gameStatus shouldBe InProgress
      g.moveCount shouldBe 0
    }

    "hand the uncontested pot to the last player and deal the next hand on a fold" in {
      val g = headsUp.play(0, move(Fold, full)).value

      val award = g.handResult.value.awards.head
      award.amount shouldBe 15
      award.winners shouldBe List(1)
      award.description shouldBe None // mucked, no showdown

      g.winner shouldBe None // both still have chips, a new hand is under way
      g.button shouldBe 1 // button rotated
      g.stacks shouldBe Vector(985, 1000) // hand 2 blinds already posted (button/SB 1, BB 0)
    }

    "award the pot to the best five-card hand at showdown" in {
      val deck = cards("AS", "AH", "KS", "KH", "AD", "7C", "2D", "5S", "9H")
      val start = TexasHoldEm.newGame(Seq(alice, bob), deck) // seat 0: AsAh, seat 1: KsKh, board Ad 7c 2d 5s 9h
      val g = playAll(
        start,
        (0, Call),
        (1, Check),
        (1, Check),
        (0, Check),
        (1, Check),
        (0, Check),
        (1, Check),
        (0, Check)
      )
      val award = g.handResult.value.awards.head
      award.amount shouldBe 20
      award.winners shouldBe List(0)
      award.description.value should startWith("three of a kind")
    }

    "reject a move out of turn" in {
      headsUp.play(1, move(Check)) shouldBe Left(InvalidTurn)
    }

    "reject checking while facing a bet" in {
      headsUp.play(0, move(Check)) shouldBe Left(CannotCheck)
    }

    "reject betting when there is already a bet" in {
      headsUp.play(0, move(Bet(50))) shouldBe Left(BetNotAllowed)
    }

    "reject a raise below the minimum" in {
      headsUp.play(0, move(Raise(15))) shouldBe Left(RaiseTooSmall) // min raise is to 20
    }

    "reject a raise that does not exceed the current bet" in {
      headsUp.play(0, move(Raise(10))) shouldBe Left(RaiseTooSmall) // equal to the current bet, not a raise
    }

    "reject a raise committing more chips than the stack holds" in {
      headsUp.play(0, move(Raise(5000))) shouldBe Left(InsufficientChips)
    }

    "reject an opening bet below the big blind on a later street" in {
      val flop = playAll(headsUp, (0, Call), (1, Check)) // both check to the flop, where the current bet is zero
      flop.street shouldBe Street.Flop
      flop.play(flop.toAct, move(Bet(5))) shouldBe Left(BetTooSmall)
    }

    "reject an opening bet larger than the stack" in {
      val flop = playAll(headsUp, (0, Call), (1, Check))
      flop.play(flop.toAct, move(Bet(5000))) shouldBe Left(InsufficientChips)
    }

    "reject a raise with no bet to raise" in {
      val flop = playAll(headsUp, (0, Call), (1, Check))
      flop.play(flop.toAct, move(Raise(20))) shouldBe Left(RaiseNotAllowed)
    }

    "accept a legal minimum raise" in {
      val raised = headsUp.play(0, move(Raise(20))).value
      raised.currentBet shouldBe 20
      raised.toAct shouldBe 1
    }

    "allow a short all-in raise and leave the minimum raise unchanged" in {
      // facing a bet of 10, a 13-chip stack can shove for a 3-chip raise even though the minimum raise is 10
      val start = table(
        ids = Vector(alice, bob),
        stacks = Vector(13, 200),
        holeCards = Vector(cards("AS", "AD"), cards("KS", "KD")),
        board = cards("2C", "7D", "9S", "3H", "5C"),
        toAct = 0,
        currentBet = 10
      )
      val shoved = start.play(0, move(Raise(13))).value
      shoved.currentBet shouldBe 13
      shoved.allIn(0) shouldBe true
      shoved.lastRaiseSize shouldBe 10 // a short all-in does not raise the minimum
    }

    "split the pot into a main pot and side pots by eligibility" in {
      // seat 0 all-in for 20, seat 1 all-in for 60, seat 2 covers with chips to spare
      val start = table(
        ids = Vector(alice, bob, carol),
        stacks = Vector(20, 60, 100),
        holeCards = Vector(cards("AS", "AD"), cards("KS", "KD"), cards("QS", "QD")),
        board = cards("AH", "2C", "7D", "9S", "3H") // seat 0 trips aces, seat 1 pair kings, seat 2 pair queens
      )
      val g = start
        .play(0, move(Bet(20)))
        .value
        .play(1, move(Raise(60)))
        .value
        .play(2, move(Call, full))
        .value

      val awards = g.handResult.value.awards
      awards.map(_.amount) shouldBe List(60, 80) // main 20*3, side 40*2
      awards.head.winners shouldBe List(0) // trips aces take the main pot
      awards(1).winners shouldBe List(1) // kings beat queens for the side pot
    }

    "split a tied pot evenly and give the odd chip to the first winner left of the button" in {
      // seats 0 and 1 both play the board straight; seat 2 has folded; the 15-chip pot splits 7/8
      val start = table(
        ids = Vector(alice, bob, carol),
        stacks = Vector(95, 95, 95),
        holeCards = Vector(cards("KS", "KD"), cards("QS", "QD"), cards("7H", "8H")),
        board = cards("2S", "3S", "4D", "5C", "6H"),
        street = Street.River,
        committed = Some(Vector(5, 5, 5)),
        folded = Some(Vector(false, false, true))
      )
      val g = playAll(start, (0, Check), (1, Check))

      val award = g.handResult.value.awards.head
      award.amount shouldBe 15
      award.winners shouldBe List(0, 1)
      // showdown stacks 102/103/95, then hand 2 blinds (button 1, SB seat 2, BB seat 0): the odd chip sat with seat 1
      g.stacks shouldBe Vector(92, 103, 90)
    }

    "end the game when one player wins every chip" in {
      val start = table(
        ids = Vector(alice, bob),
        stacks = Vector(1000, 1000),
        holeCards = Vector(cards("AS", "AD"), cards("KS", "KD")),
        board = cards("AH", "2C", "7D", "9S", "3H") // seat 0 makes trips aces
      )
      val g = start.play(0, move(Bet(1000))).value.play(1, move(Call, full)).value

      g.winner shouldBe Some(0)
      g.gameStatus shouldBe Won(0)
      g.stacks shouldBe Vector(2000, 0)
    }

    "reject any move once the game is over" in {
      val start = table(
        ids = Vector(alice, bob),
        stacks = Vector(1000, 1000),
        holeCards = Vector(cards("AS", "AD"), cards("KS", "KD")),
        board = cards("AH", "2C", "7D", "9S", "3H")
      )
      val over = start.play(0, move(Bet(1000))).value.play(1, move(Call, full)).value
      over.play(0, move(Check)) shouldBe Left(GameOver)
    }

    "run the next hand to showdown when the blinds put both remaining players all-in" in {
      // both players are shorter than the blinds, so the next hand is dealt straight to a showdown
      val nextHand = cards("AS", "AD", "5C", "7D", "AH", "AC", "2S", "3H", "4D") // seat 0 flops quad aces
      val start = table(
        ids = Vector(alice, bob),
        stacks = Vector(5, 3),
        holeCards = Vector(cards("2H", "3S"), cards("4H", "5D")),
        board = cards("KS", "KD", "KC", "2D", "2C")
      )
      val g = start.play(0, move(Fold, nextHand)).value

      g.winner shouldBe Some(0)
      g.stacks shouldBe Vector(8, 0)
    }

    "count only player actions in moveCount" in {
      val g = playAll(headsUp, (0, Call), (1, Check))
      g.moveCount shouldBe 2
    }
  }

  "A leaving player" should {

    "be rejected for a non-participant" in {
      val stranger = UUID.randomUUID()
      headsUp.playerLeft(stranger) shouldBe Left(InvalidPlayer(stranger))
    }

    "be rejected once the game is already over" in {
      val over = headsUp.playerLeft(alice).value
      over.playerLeft(bob) shouldBe Left(GameOver)
    }

    "hand the win to the last remaining player when only two are left" in {
      val g = headsUp.playerLeft(alice).value
      g.winner shouldBe Some(1)
      g.gameStatus shouldBe Won(1)
    }

    "continue the hand when players remain and the leaver was not on the clock" in {
      // seat 1 is the small blind, seat 0 (UTG) is to act
      val g = TexasHoldEm.newGame(Seq(alice, bob, carol), full).playerLeft(bob).value
      g.winner shouldBe None
      g.folded(1) shouldBe true
      g.stacks(1) shouldBe 0
      g.toAct shouldBe 0
    }

    "award the pot and deal the next hand from the unseen deck when the leave ends the hand" in {
      val start = table(
        ids = Vector(alice, bob, carol),
        stacks = Vector(500, 500, 500),
        holeCards = Vector(cards("2S", "3S"), cards("KS", "KD"), cards("QS", "QD")),
        board = cards("AH", "2C", "7D", "9S", "3H"),
        toAct = 1,
        street = Street.Flop,
        committed = Some(Vector(10, 20, 20)),
        folded = Some(Vector(true, false, false)), // seat 0 already folded
        deck = full
      )
      val g = start.playerLeft(carol).value // only seat 1 remains in the hand

      g.winner shouldBe None // seats 0 and 1 still have chips, so the sit-and-go continues
      g.handResult.value.awards.head.winners shouldBe List(1)
      g.stacks(2) shouldBe 0 // the leaver is out
      g.board.size shouldBe 5 // a fresh hand was dealt from the remaining deck
    }
  }

  "TexasHoldEm.newGame" should {

    "post the blinds heads-up and have the button act first" in {
      val g = TexasHoldEm.newGame(Seq(alice, bob), full)
      g.stacks shouldBe Vector(995, 990) // button/SB 0, BB 1
      g.currentBet shouldBe 10
      g.toAct shouldBe 0 // heads-up, the button acts first pre-flop
    }

    "post blinds left of the button and let the seat after the big blind act first three-handed" in {
      val g = TexasHoldEm.newGame(Seq(alice, bob, carol), full)
      g.stacks shouldBe Vector(1000, 995, 990) // button 0, SB 1, BB 2
      g.currentBet shouldBe 10
      g.toAct shouldBe 0 // UTG = seat after the big blind
    }
  }

  "Street.next" should {
    "advance through the betting streets and stop after the river" in {
      Street.next(Street.PreFlop) shouldBe Some(Street.Flop)
      Street.next(Street.Flop) shouldBe Some(Street.Turn)
      Street.next(Street.Turn) shouldBe Some(Street.River)
      Street.next(Street.River) shouldBe None
    }
  }
}
