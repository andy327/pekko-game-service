package com.andy327.model.mastermind

import java.util.UUID

import scala.util.Random

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{InProgress, PlayerId, Won}

class MastermindSpec extends AnyWordSpec with Matchers {
  import Peg._

  val alice: PlayerId = UUID.randomUUID() // codemaker (seat 0)
  val bob: PlayerId = UUID.randomUUID() // codebreaker (seat 1)

  private def newGame: Mastermind = Mastermind.newGame(Seq(alice, bob))

  /** A game with the code already set, ready for the codebreaker to guess. */
  private def withCode(code: Peg*): Mastermind = newGame.play(Codemaker, SetCode(code.toVector)).toOption.get

  "A Mastermind game" should {

    "resolve participants to their roles with playerFor" in {
      newGame.playerFor(alice) shouldBe Some(Codemaker)
      newGame.playerFor(bob) shouldBe Some(Codebreaker)
      newGame.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      newGame.players shouldBe List(alice, bob)
    }

    "start awaiting the codemaker's secret code" in {
      val game = newGame
      game.currentPlayer shouldBe Codemaker
      game.secret shouldBe None
      game.moveCount shouldBe 0
      game.gameStatus shouldBe InProgress
    }

    "fix the code and hand the turn to the codebreaker on SetCode" in {
      val Right(next) = newGame.play(Codemaker, SetCode(Vector(Red, Green, Yellow, Blue)))
      next.secret shouldBe Some(Vector(Red, Green, Yellow, Blue))
      next.currentPlayer shouldBe Codebreaker
      next.moveCount shouldBe 1
    }

    "reject a code that is not the required length" in {
      newGame.play(Codemaker, SetCode(Vector(Red, Green))) shouldBe Left(InvalidCodeLength)
    }

    "reject the codebreaker acting before the code is set" in {
      newGame.play(Codebreaker, Guess(Vector(Red, Red, Red, Red))) shouldBe Left(InvalidTurn)
    }

    "reject the codemaker guessing instead of setting the code" in {
      newGame.play(Codemaker, Guess(Vector(Red, Red, Red, Red))) shouldBe Left(CodeNotSet)
    }

    "reject setting the code twice" in {
      withCode(Red, Green, Yellow, Blue).play(Codebreaker, SetCode(Vector(Red, Red, Red, Red))) shouldBe
        Left(CodeAlreadySet)
    }

    "record a guess with feedback and stay in progress when wrong" in {
      val Right(next) = withCode(Red, Green, Yellow, Blue).play(Codebreaker, Guess(Vector(Red, Green, Blue, Yellow)))
      next.guesses shouldBe List(Attempt(Vector(Red, Green, Blue, Yellow), Feedback(2, 2)))
      next.currentPlayer shouldBe Codebreaker
      next.gameStatus shouldBe InProgress
      next.moveCount shouldBe 2 // set-code + one guess
    }

    "declare the codebreaker the winner on an exact match" in {
      val Right(next) = withCode(Red, Green, Yellow, Blue).play(Codebreaker, Guess(Vector(Red, Green, Yellow, Blue)))
      next.winner shouldBe Some(Codebreaker)
      next.gameStatus shouldBe Won(Codebreaker)
    }

    "declare the codemaker the winner when the guesses run out" in {
      val wrong = Guess(Vector(Green, Green, Green, Green))
      val exhausted = (1 to Mastermind.MaxGuesses).foldLeft(withCode(Red, Red, Red, Red)) { (g, _) =>
        g.play(Codebreaker, wrong).toOption.get
      }
      exhausted.guesses.size shouldBe Mastermind.MaxGuesses
      exhausted.winner shouldBe Some(Codemaker)
      exhausted.gameStatus shouldBe Won(Codemaker)
    }

    "reject a guess that is not the required length" in {
      withCode(Red, Green, Yellow, Blue).play(Codebreaker, Guess(Vector(Red, Green))) shouldBe Left(InvalidCodeLength)
    }

    "reject the codemaker guessing out of turn once the code is set" in {
      withCode(Red, Green, Yellow, Blue).play(Codemaker, Guess(Vector(Red, Green, Yellow, Blue))) shouldBe
        Left(InvalidTurn)
    }

    "reject any further move once the game is over" in {
      val won =
        withCode(Red, Green, Yellow, Blue).play(Codebreaker, Guess(Vector(Red, Green, Yellow, Blue))).toOption.get
      won.play(Codebreaker, Guess(Vector(Red, Red, Red, Red))) shouldBe Left(GameOver)
    }

    "count the set-code move and each guess in moveCount" in {
      val Right(afterCode) = newGame.play(Codemaker, SetCode(Vector(Red, Green, Yellow, Blue)))
      afterCode.moveCount shouldBe 1
      val Right(afterGuess) = afterCode.play(Codebreaker, Guess(Vector(Blue, Yellow, Green, Red)))
      afterGuess.moveCount shouldBe 2
    }
  }

  "A Mastermind Peg" should {
    "render with its color name" in {
      Red.toString shouldBe "red"
      Black.toString shouldBe "black"
      White.toString shouldBe "white"
    }
  }

  "A Mastermind Role" should {
    "render with its label" in {
      Codemaker.toString shouldBe "codemaker"
      Codebreaker.toString shouldBe "codebreaker"
    }
  }

  "Mastermind.feedback" should {

    "count black pegs for the right color in the right position" in {
      Mastermind.feedback(Vector(Red, Green, Yellow, Blue), Vector(Red, Green, Yellow, Blue)) shouldBe Feedback(4, 0)
    }

    "count white pegs for the right color in the wrong position" in {
      Mastermind.feedback(Vector(Red, Green, Yellow, Blue), Vector(Blue, Yellow, Green, Red)) shouldBe Feedback(0, 4)
    }

    "not count a color more times than it appears in the secret" in {
      // the secret has one Red; three Reds in the guess still yield a single match (the black one)
      Mastermind.feedback(Vector(Red, Green, Green, Green), Vector(Red, Red, Red, Red)) shouldBe Feedback(1, 0)
    }

    "split repeated colors across black and white correctly" in {
      Mastermind.feedback(Vector(Red, Red, Green, Blue), Vector(Red, Green, Red, Blue)) shouldBe Feedback(2, 2)
    }
  }

  "A leaving player" should {

    "forfeit to the other role" in {
      newGame.playerLeft(alice).map(_.gameStatus) shouldBe Right(Won(Codebreaker))
      newGame.playerLeft(bob).map(_.gameStatus) shouldBe Right(Won(Codemaker))
    }

    "be rejected for a non-participant" in {
      newGame.playerLeft(UUID.randomUUID()) shouldBe a[Left[_, _]]
    }

    "be rejected once the game is already over" in {
      val won =
        withCode(Red, Green, Yellow, Blue).play(Codebreaker, Guess(Vector(Red, Green, Yellow, Blue))).toOption.get
      won.playerLeft(bob) shouldBe Left(GameOver)
    }
  }

  "Mastermind.randomCode" should {
    "produce a code of the required length using only palette colors" in {
      val code = Mastermind.randomCode(new Random(7))
      code.size shouldBe Mastermind.CodeLength
      code.foreach(Peg.all should contain(_))
    }
  }
}
