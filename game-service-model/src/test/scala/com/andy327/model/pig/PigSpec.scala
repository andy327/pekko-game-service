package com.andy327.model.pig

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{InProgress, PlayerId, Won}

class PigSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()
  val carol: PlayerId = UUID.randomUUID()

  "A Pig game" should {

    "resolve participants to their seat indices with playerFor" in {
      val game = Pig.newGame(Seq(alice, bob))
      game.playerFor(alice) shouldBe Some(0)
      game.playerFor(bob) shouldBe Some(1)
      game.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      Pig.newGame(Seq(alice, bob)).players shouldBe List(alice, bob)
    }

    "start with all scores at zero, no last roll, and the first player to move" in {
      val game = Pig.newGame(Seq(alice, bob))
      game.scores shouldBe Vector(0, 0)
      game.turnScore shouldBe 0
      game.lastRoll shouldBe None
      game.currentSeat shouldBe 0
      game.currentPlayer shouldBe 0
      game.moveCount shouldBe 0
      game.gameStatus shouldBe InProgress
    }

    "add a roll of 2–6 to the turn score and keep the turn" in {
      val game = Pig.newGame(Seq(alice, bob))
      val Right(next) = game.play(0, Roll(4))
      next.turnScore shouldBe 4
      next.lastRoll shouldBe Some(4)
      next.currentSeat shouldBe 0
      next.scores shouldBe Vector(0, 0)
      next.gameStatus shouldBe InProgress
    }

    "accumulate turn score across multiple rolls" in {
      val game = Pig.newGame(Seq(alice, bob))
      val Right(after3) = game.play(0, Roll(3))
      val Right(after5) = after3.play(0, Roll(5))
      after5.turnScore shouldBe 8
      after5.currentSeat shouldBe 0
    }

    "keep the game in progress when accumulated rolls would reach the win threshold before Hold" in {
      val game = Pig.newGame(Seq(alice, bob)).copy(scores = Vector(95, 0))
      val Right(next) = game.play(0, Roll(5))
      next.gameStatus shouldBe InProgress
      next.turnScore shouldBe 5
      next.winner shouldBe None
    }

    "bust on a roll of 1 — lose the turn score and pass to the next player" in {
      val game = Pig.newGame(Seq(alice, bob))
      val Right(after4) = game.play(0, Roll(4))
      val Right(bust) = after4.play(0, Roll(1))
      bust.turnScore shouldBe 0
      bust.lastRoll shouldBe Some(1)
      bust.currentSeat shouldBe 1
      bust.scores shouldBe Vector(0, 0)
    }

    "not bank any accumulated points when busting" in {
      val game = Pig.newGame(Seq(alice, bob)).copy(scores = Vector(50, 0))
      val Right(after6) = game.play(0, Roll(6))
      val Right(bust) = after6.play(0, Roll(1))
      bust.scores shouldBe Vector(50, 0)
      bust.currentSeat shouldBe 1
    }

    "bank the turn score and advance on Hold" in {
      val game = Pig.newGame(Seq(alice, bob))
      val Right(rolled) = game.play(0, Roll(5))
      val Right(held) = rolled.play(0, Hold)
      held.scores shouldBe Vector(5, 0)
      held.turnScore shouldBe 0
      held.lastRoll shouldBe None
      held.currentSeat shouldBe 1
    }

    "end the game when the banked total reaches the win threshold on Hold" in {
      val game = Pig.newGame(Seq(alice, bob)).copy(scores = Vector(95, 0))
      val Right(rolled) = game.play(0, Roll(5))
      val Right(held) = rolled.play(0, Hold)
      held.gameStatus shouldBe Won(0)
      held.scores(0) shouldBe 100
      held.winner shouldBe Some(0)
    }

    "end the game when the banked total exceeds the win threshold on Hold" in {
      val game = Pig.newGame(Seq(alice, bob)).copy(scores = Vector(95, 0))
      val Right(rolled) = game.play(0, Roll(6))
      val Right(held) = rolled.play(0, Hold)
      held.gameStatus shouldBe Won(0)
      held.scores(0) shouldBe 101
    }

    "reject Hold when no roll has been made this turn" in {
      val game = Pig.newGame(Seq(alice, bob))
      game.play(0, Hold) shouldBe Left(NothingToHold)
    }

    "wrap the turn back to the first seat after the last" in {
      val game = Pig.newGame(Seq(alice, bob))
      val Right(r1) = game.play(0, Roll(3))
      val Right(h1) = r1.play(0, Hold)
      h1.currentSeat shouldBe 1
      val Right(r2) = h1.play(1, Roll(2))
      val Right(h2) = r2.play(1, Hold)
      h2.currentSeat shouldBe 0
    }

    "advance through seats in order with three players" in {
      val game = Pig.newGame(Seq(alice, bob, carol))
      val Right(r0) = game.play(0, Roll(3))
      val Right(h0) = r0.play(0, Hold)
      h0.currentSeat shouldBe 1
      val Right(r1) = h0.play(1, Roll(2))
      val Right(h1) = r1.play(1, Hold)
      h1.currentSeat shouldBe 2
      val Right(r2) = h1.play(2, Roll(5))
      val Right(h2) = r2.play(2, Hold)
      h2.currentSeat shouldBe 0
    }

    "reject a move by the out-of-turn player" in {
      val game = Pig.newGame(Seq(alice, bob))
      game.play(1, Roll(4)) shouldBe Left(InvalidTurn)
    }

    "reject any move once the game is over" in {
      val game = Pig.newGame(Seq(alice, bob)).copy(scores = Vector(95, 0))
      val Right(rolled) = game.play(0, Roll(5))
      val Right(finished) = rolled.play(0, Hold)
      finished.gameStatus shouldBe Won(0)
      finished.play(0, Roll(3)) shouldBe Left(GameOver)
      finished.play(0, Hold) shouldBe Left(GameOver)
    }

    "increment moveCount on every Roll and Hold" in {
      val game = Pig.newGame(Seq(alice, bob))
      val Right(g1) = game.play(0, Roll(3))
      g1.moveCount shouldBe 1
      val Right(g2) = g1.play(0, Roll(1)) // bust
      g2.moveCount shouldBe 2
      val Right(g3) = g2.play(1, Roll(4))
      g3.moveCount shouldBe 3
      val Right(g4) = g3.play(1, Hold)
      g4.moveCount shouldBe 4
    }
  }

  "A leaving player" should {

    "award the win to the opponent in a two-player game" in {
      val game = Pig.newGame(Seq(alice, bob))
      game.playerLeft(alice).map(_.gameStatus) shouldBe Right(Won(1))
      game.playerLeft(bob).map(_.gameStatus) shouldBe Right(Won(0))
    }

    "award the win to the highest-scoring non-leaver in a multiplayer game" in {
      val game = Pig.newGame(Seq(alice, bob, carol)).copy(scores = Vector(40, 70, 30))
      game.playerLeft(alice).map(_.winner) shouldBe Right(Some(1))
    }

    "break a tie in favour of the lower seat index" in {
      val game = Pig.newGame(Seq(alice, bob, carol)).copy(scores = Vector(50, 50, 30))
      game.playerLeft(carol).map(_.winner) shouldBe Right(Some(0))
    }

    "be rejected for a non-participant" in {
      val game = Pig.newGame(Seq(alice, bob))
      game.playerLeft(UUID.randomUUID()) shouldBe a[Left[_, _]]
    }

    "be rejected once the game is already over" in {
      val game = Pig.newGame(Seq(alice, bob)).copy(scores = Vector(95, 0))
      val Right(rolled) = game.play(0, Roll(5))
      val Right(finished) = rolled.play(0, Hold)
      finished.playerLeft(bob) shouldBe Left(GameOver)
    }
  }

  "Pig.newGame" should {

    "seat players in the order provided" in {
      val game = Pig.newGame(Seq(alice, bob, carol))
      game.playerFor(alice) shouldBe Some(0)
      game.playerFor(bob) shouldBe Some(1)
      game.playerFor(carol) shouldBe Some(2)
    }

    "support up to 8 players" in {
      val players = Seq.fill(8)(UUID.randomUUID())
      val game = Pig.newGame(players)
      game.playerIds.size shouldBe 8
      game.scores shouldBe Vector.fill(8)(0)
    }
  }
}
