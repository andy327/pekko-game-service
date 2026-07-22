package com.andy327.model.liarsdice

import java.util.UUID

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{InProgress, PlayerId, Won}

class LiarsDiceSpec extends AnyWordSpec with Matchers with OptionValues {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()
  val carol: PlayerId = UUID.randomUUID()

  // A pool large enough to deal any hand; only the dealt prefixes ever matter, so exact values are set per test.
  private val pool: List[Int] = List.fill(LiarsDice.MaxTotalDice)(2)

  /** Builds a two-player game with explicit dice for each seat and no standing bid, seat 0 to act. */
  private def game(seat0: Vector[Int], seat1: Vector[Int]): LiarsDice =
    LiarsDice(Vector(alice, bob), Vector(seat0, seat1), 0, None, None, None, 0)

  "A LiarsDice game" should {

    "resolve participants to their seat indices with playerFor" in {
      val g = game(Vector(2, 3), Vector(4, 5))
      g.playerFor(alice) shouldBe Some(0)
      g.playerFor(bob) shouldBe Some(1)
      g.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      game(Vector(2, 3), Vector(4, 5)).players shouldBe List(alice, bob)
    }

    "start with a full hand, no standing bid, and the first player to move" in {
      val g = LiarsDice.newGame(Seq(alice, bob), pool)
      g.currentSeat shouldBe 0
      g.currentPlayer shouldBe 0
      g.standing shouldBe None
      g.lastReveal shouldBe None
      g.moveCount shouldBe 0
      g.gameStatus shouldBe InProgress
    }

    "accept any well-formed opening bid and pass the turn" in {
      val Right(next) = game(Vector(2, 3), Vector(4, 5)).play(0, MakeBid(Bid(2, Some(4))))
      next.standing shouldBe Some(StandingBid(Bid(2, Some(4)), 0))
      next.currentSeat shouldBe 1
      next.moveCount shouldBe 1
    }

    "reject a malformed bid" in {
      game(Vector(2), Vector(3)).play(0, MakeBid(Bid(0, Some(4)))) shouldBe Left(InvalidBid)
      game(Vector(2), Vector(3)).play(0, MakeBid(Bid(2, Some(7)))) shouldBe Left(InvalidBid)
    }

    "reject a challenge when there is no standing bid" in {
      game(Vector(2), Vector(3)).play(0, Challenge(pool)) shouldBe Left(NoBidToChallenge)
    }

    "reject a move made out of turn" in {
      game(Vector(2), Vector(3)).play(1, MakeBid(Bid(1, Some(2)))) shouldBe Left(InvalidTurn)
    }

    "accept a legal raise and reject an illegal one" in {
      val g = game(Vector(2, 3), Vector(4, 5)).copy(standing = Some(StandingBid(Bid(2, Some(4)), 0)), currentSeat = 1)
      g.play(1, MakeBid(Bid(2, Some(5)))).map(_.standing) shouldBe Right(Some(StandingBid(Bid(2, Some(5)), 1)))
      g.play(1, MakeBid(Bid(2, Some(3)))) shouldBe Left(IllegalRaise)
    }

    "penalise the challenger by the overshoot when the bid is good" in {
      // bid "3 fours"; dice hold four 4s and one 1 => count 5, overshoot 2, so the challenger (seat 1) loses 2 dice
      val g = game(Vector(4, 4, 4), Vector(4, 1))
        .copy(standing = Some(StandingBid(Bid(3, Some(4)), 0)), currentSeat = 1)
      val Right(next) = g.play(1, Challenge(pool))
      val reveal = next.lastReveal.value
      reveal.count shouldBe 5
      reveal.loserSeat shouldBe 1
      reveal.diceLost shouldBe 2
      next.diceCount(0) shouldBe 3
      next.diceCount(1) shouldBe 0 // eliminated (had 2, lost 2)
      next.gameStatus shouldBe Won(0)
    }

    "penalise the bidder a single die when the bid is too high" in {
      // bid "4 sixes"; only one 6 and no wild 1s => count 1 < 4, so the bidder (seat 0) loses one die
      val g = game(Vector(6, 2, 3), Vector(2, 2, 3))
        .copy(standing = Some(StandingBid(Bid(4, Some(6)), 0)), currentSeat = 1)
      val Right(next) = g.play(1, Challenge(List.fill(LiarsDice.MaxTotalDice)(2)))
      next.lastReveal.value.loserSeat shouldBe 0
      next.lastReveal.value.diceLost shouldBe 1
      next.diceCount(0) shouldBe 2
      next.diceCount(1) shouldBe 3
      next.currentSeat shouldBe 0 // the loser starts the next round
      next.standing shouldBe None
      next.gameStatus shouldBe InProgress
    }

    "cost the challenger no dice when the count meets the bid exactly" in {
      // bid "2 fours"; exactly two 4s => count 2 == quantity, so the challenger loses zero dice but still loses the round
      val g = game(Vector(4, 2, 3), Vector(4, 5, 6))
        .copy(standing = Some(StandingBid(Bid(2, Some(4)), 0)), currentSeat = 1)
      val Right(next) = g.play(1, Challenge(pool))
      next.lastReveal.value.diceLost shouldBe 0
      next.lastReveal.value.loserSeat shouldBe 1
      next.diceCount(1) shouldBe 3
      next.currentSeat shouldBe 1 // the losing challenger starts the next round
      next.gameStatus shouldBe InProgress
    }

    "re-roll every surviving hand from the supplied pool" in {
      val g = game(Vector(6, 2), Vector(2, 3))
        .copy(standing = Some(StandingBid(Bid(4, Some(6)), 0)), currentSeat = 1)
      val Right(next) = g.play(1, Challenge(List.fill(LiarsDice.MaxTotalDice)(5)))
      next.dice.flatten.forall(_ == 5) shouldBe true // fresh dice replaced the old hands
      next.diceCount(0) shouldBe 1 // bidder lost one die
      next.diceCount(1) shouldBe 2
    }

    "start the next round from the next active seat when the loser is eliminated" in {
      // three players; the challenger (seat 2) is wrong and is eliminated, so seat 0 (clockwise from 2) starts
      val g = LiarsDice(
        Vector(alice, bob, carol),
        Vector(Vector(4, 4, 4), Vector(4, 4, 4), Vector(2)),
        2,
        Some(StandingBid(Bid(4, Some(4)), 0)),
        None,
        None,
        0
      )
      val Right(next) = g.play(2, Challenge(pool))
      next.diceCount(2) shouldBe 0
      next.currentSeat shouldBe 0
      next.gameStatus shouldBe InProgress // two players remain
    }

    "skip an already-eliminated seat when advancing the turn, wrapping around the table" in {
      // three players with seat 2 already out; seat 1 bids, so the turn skips the empty seat 2 and wraps to seat 0
      val g =
        LiarsDice(Vector(alice, bob, carol), Vector(Vector(2, 3), Vector(4, 5), Vector.empty), 1, None, None, None, 0)
      val Right(next) = g.play(1, MakeBid(Bid(1, Some(2))))
      next.currentSeat shouldBe 0
    }

    "reject any move once the game is over" in {
      val finished = game(Vector(2), Vector(3)).copy(winner = Some(0))
      finished.play(0, MakeBid(Bid(1, Some(2)))) shouldBe Left(GameOver)
      finished.play(0, Challenge(pool)) shouldBe Left(GameOver)
    }
  }

  "A LiarsDice Bid" should {

    "allow a higher face on the same quantity" in {
      Bid(3, Some(4)).canRaiseTo(Bid(3, Some(5))) shouldBe true
      Bid(3, Some(4)).canRaiseTo(Bid(3, Some(6))) shouldBe true
    }

    "reject the same or a lower face on the same quantity" in {
      Bid(3, Some(4)).canRaiseTo(Bid(3, Some(4))) shouldBe false
      Bid(3, Some(4)).canRaiseTo(Bid(3, Some(3))) shouldBe false
    }

    "allow a higher quantity only if the face is not lowered" in {
      Bid(3, Some(4)).canRaiseTo(Bid(4, Some(4))) shouldBe true
      Bid(3, Some(4)).canRaiseTo(Bid(4, Some(5))) shouldBe true
      Bid(3, Some(4)).canRaiseTo(Bid(4, Some(3))) shouldBe false // higher quantity, lower face
    }

    "reject a lower quantity (counter-clockwise)" in {
      Bid(4, Some(4)).canRaiseTo(Bid(3, Some(6))) shouldBe false
    }

    "allow advancing to any clockwise-later ones space, but not an earlier one" in {
      // "3 fours" sits at rank 6; "2 ones" sits at rank 7 (clockwise-later); "1 one" sits at rank 3 (earlier)
      Bid(3, Some(4)).canRaiseTo(Bid(2, None)) shouldBe true
      Bid(3, Some(4)).canRaiseTo(Bid(1, None)) shouldBe false
    }

    "allow leaving a ones bid to any face once the quantity reaches the following number" in {
      // out of "1 one" the smallest numbered bid is 2 of any face
      Bid(1, None).canRaiseTo(Bid(2, Some(2))) shouldBe true
      Bid(1, None).canRaiseTo(Bid(2, Some(6))) shouldBe true
      Bid(1, None).canRaiseTo(Bid(1, Some(6))) shouldBe false // quantity 1 is before the ones space
    }

    "order ones bids by their count" in {
      Bid(1, None).canRaiseTo(Bid(2, None)) shouldBe true
      Bid(2, None).canRaiseTo(Bid(1, None)) shouldBe false
    }

    "match the worked example from the rules (3 fours)" in {
      val from = Bid(3, Some(4))
      val legal = Seq(Bid(3, Some(5)), Bid(3, Some(6)), Bid(2, None), Bid(4, Some(4)), Bid(4, Some(5)), Bid(4, Some(6)))
      legal.foreach(b => withClue(b)(from.canRaiseTo(b) shouldBe true))
      from.canRaiseTo(Bid(4, Some(3))) shouldBe false // "4 threes" is explicitly illegal
    }
  }

  "A leaving player" should {

    "award the win to the last remaining player" in {
      game(Vector(2, 3), Vector(4, 5)).playerLeft(alice).map(_.gameStatus) shouldBe Right(Won(1))
      game(Vector(2, 3), Vector(4, 5)).playerLeft(bob).map(_.gameStatus) shouldBe Right(Won(0))
    }

    "keep a multiplayer game going, clearing the bid and advancing off a leaver who was on the clock" in {
      val g = LiarsDice(
        Vector(alice, bob, carol),
        Vector(Vector(2, 3), Vector(4, 5), Vector(6, 1)),
        0,
        Some(StandingBid(Bid(1, Some(2)), 2)),
        None,
        None,
        3
      )
      val Right(next) = g.playerLeft(alice)
      next.diceCount(0) shouldBe 0
      next.standing shouldBe None
      next.currentSeat shouldBe 1 // advanced off the leaving seat
      next.gameStatus shouldBe InProgress
    }

    "leave the turn unchanged when the leaver was not on the clock" in {
      // seat 1 is to act; seat 2 leaves, so the turn stays on seat 1 (only the leaver's dice are set aside)
      val g = LiarsDice(
        Vector(alice, bob, carol),
        Vector(Vector(2, 3), Vector(4, 5), Vector(6, 1)),
        1,
        Some(StandingBid(Bid(1, Some(2)), 0)),
        None,
        None,
        3
      )
      val Right(next) = g.playerLeft(carol)
      next.diceCount(2) shouldBe 0
      next.currentSeat shouldBe 1 // unchanged — the leaver was not the current player
      next.gameStatus shouldBe InProgress
    }

    "be rejected for a non-participant" in {
      game(Vector(2), Vector(3)).playerLeft(UUID.randomUUID()) shouldBe a[Left[_, _]]
    }

    "be rejected once the game is over" in {
      game(Vector(2), Vector(3)).copy(winner = Some(0)).playerLeft(bob) shouldBe Left(GameOver)
    }
  }

  "LiarsDice.newGame" should {

    "deal every player five dice and seat player one to open" in {
      val g = LiarsDice.newGame(Seq(alice, bob, carol), List.fill(LiarsDice.MaxTotalDice)(3))
      g.dice.map(_.size) shouldBe Vector(5, 5, 5)
      g.dice.flatten.forall(_ == 3) shouldBe true
      g.currentSeat shouldBe 0
    }

    "seat players in the order provided" in {
      val g = LiarsDice.newGame(Seq(alice, bob, carol), pool)
      g.playerFor(alice) shouldBe Some(0)
      g.playerFor(bob) shouldBe Some(1)
      g.playerFor(carol) shouldBe Some(2)
    }

    "support up to six players" in {
      val players = Seq.fill(6)(UUID.randomUUID())
      val g = LiarsDice.newGame(players, pool)
      g.playerIds.size shouldBe 6
      g.dice.map(_.size) shouldBe Vector.fill(6)(LiarsDice.DicePerPlayer)
    }
  }

  "LiarsDice.legalBids" should {

    "offer every well-formed bid up to the table's dice when opening a round" in {
      val g = game(Vector(2, 3), Vector(4, 5)) // 4 dice on the table, no standing bid
      val bids = g.legalBids
      bids should have size (4 * 6).toLong // quantities 1–4, each with faces 2–6 or wild ones
      bids.foreach(bid => bid.isWellFormed shouldBe true)
      bids.map(_.quantity).max shouldBe 4
    }

    "offer exactly the raises the standing bid allows" in {
      val g = game(Vector(2, 3), Vector(4, 5)).copy(standing = Some(StandingBid(Bid(2, Some(4)), 1)))
      val bids = g.legalBids
      bids.foreach(bid => Bid(2, Some(4)).canRaiseTo(bid) shouldBe true)
      bids should contain(Bid(2, Some(5))) // same quantity, higher face
      bids should not contain Bid(2, Some(3)) // same quantity, lower face
      bids should not contain Bid(1, Some(6)) // lower quantity
    }

    "agree with play about every bid it offers" in {
      val g = game(Vector(2, 3), Vector(4, 5)).copy(standing = Some(StandingBid(Bid(2, Some(4)), 1)))
      g.legalBids.foreach(bid => g.play(0, MakeBid(bid)) shouldBe a[Right[_, _]])
    }

    "cap the quantity at the table's dice even though play accepts more" in {
      val g = game(Vector(2, 3), Vector(4, 5)) // 4 dice on the table
      g.legalBids.map(_.quantity).max shouldBe 4
      // a bid beyond the cap is still accepted by play — it just can never be true, so the list omits it
      g.play(0, MakeBid(Bid(5, Some(4)))) shouldBe a[Right[_, _]]
    }

    "offer nothing once the game is over" in {
      game(Vector(2, 3), Vector.empty).copy(winner = Some(0)).legalBids shouldBe empty
    }
  }

  "LiarsDice.countToward" should {

    "count the named face plus wild ones for a numbered bid" in {
      LiarsDice.countToward(Bid(1, Some(4)), Vector(Vector(4, 1, 2), Vector(4, 1, 6))) shouldBe 4 // two 4s, two 1s
    }

    "count only the ones for a wild ones bid" in {
      LiarsDice.countToward(Bid(1, None), Vector(Vector(4, 1, 2), Vector(1, 1, 6))) shouldBe 3
    }
  }
}
