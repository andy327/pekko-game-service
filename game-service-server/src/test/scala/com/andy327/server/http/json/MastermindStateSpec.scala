package com.andy327.server.http.json

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.game.MastermindState
import com.andy327.model.core.PlayerId
import com.andy327.model.mastermind.Peg.{Blue, Green, Red, Yellow}
import com.andy327.model.mastermind.{Attempt, Codebreaker, Codemaker, Feedback, Mastermind, Role}

class MastermindStateSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID() // codemaker
  val bob: PlayerId = UUID.randomUUID() // codebreaker

  private val code = Vector(Red, Green, Yellow, Blue)
  private val oneGuess = List(Attempt(Vector(Red, Green, Blue, Yellow), Feedback(2, 2)))

  private def game(guesses: List[Attempt] = oneGuess, winner: Option[Role] = None): Mastermind =
    Mastermind(alice, bob, Some(code), guesses, winner)

  "MastermindState.of for the codemaker" should {
    "always reveal the secret and expose the public guess history" in {
      val view = MastermindState.of(game(), Some(Codemaker))
      view.viewerRole shouldBe Some(Codemaker)
      view.secret shouldBe Some(code)
      view.guesses shouldBe oneGuess
      view.currentPlayer shouldBe Codebreaker
      view.guessesRemaining shouldBe Mastermind.MaxGuesses - 1
    }
  }

  "MastermindState.of for the codebreaker" should {
    "hide the secret while the game is in progress" in {
      MastermindState.of(game(), Some(Codebreaker)).secret shouldBe None
    }
  }

  "MastermindState.of for a spectator" should {
    "hide the secret while the game is in progress" in {
      val view = MastermindState.of(game(), None)
      view.viewerRole shouldBe None
      view.secret shouldBe None
    }
  }

  "MastermindState.of once the game is over" should {
    "reveal the secret to everyone, including the codebreaker" in {
      val finished = game(winner = Some(Codebreaker))
      MastermindState.of(finished, Some(Codebreaker)).secret shouldBe Some(code)
      MastermindState.of(finished, None).secret shouldBe Some(code)
      MastermindState.of(finished, Some(Codebreaker)).winner shouldBe Some(Codebreaker)
    }
  }

  "MastermindState.of before a code is set" should {
    "report the codemaker to move and no secret for the codebreaker" in {
      val fresh = Mastermind.newGame(Seq(alice, bob))
      val view = MastermindState.of(fresh, Some(Codebreaker))
      view.currentPlayer shouldBe Codemaker
      view.secret shouldBe None
      view.guesses shouldBe empty
      view.guessesRemaining shouldBe Mastermind.MaxGuesses
    }
  }
}
