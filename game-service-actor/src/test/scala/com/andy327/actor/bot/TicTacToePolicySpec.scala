package com.andy327.actor.bot

import java.util.UUID

import scala.util.Random

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.game.MovePayload
import com.andy327.actor.game.modules.{ConnectFourModule, TicTacToeModule}
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.{GameType, PlayerId}
import com.andy327.model.tictactoe.{Location, Mark, O, TicTacToe, X}

class TicTacToePolicySpec extends AnyWordSpec with Matchers with OptionValues {

  private val alice: PlayerId = UUID.randomUUID() // X, first to move
  private val bob: PlayerId = UUID.randomUUID() // O

  private val rng = new Random(0)

  /** The move the policy plays for `viewer`, as a board location. */
  private def move(game: TicTacToe, viewer: PlayerId): Location =
    TicTacToePolicy.decide(TicTacToeModule.project(game, Some(viewer)), rng).value match {
      case MovePayload.TicTacToeMove(row, col) => Location(row, col)
      case other                               => fail(s"unexpected payload: $other")
    }

  /** Builds a game with the given player to move from three row strings, where `X`/`O` mark a cell and any other
    * character (e.g. `.`) leaves it empty.
    */
  private def board(current: Mark)(rows: String*): TicTacToe = {
    val cells = rows.toVector.map(_.toVector.map {
      case 'X' => Some(X): Option[Mark]
      case 'O' => Some(O)
      case _   => None
    })
    TicTacToe(alice, bob, cells, current, None, isDraw = false)
  }

  /** Recursively play `game` out, letting the policy move for `botMark` and branching over every opponent reply, and
    * assert the bot never loses on any line — the defining property of optimal Tic-Tac-Toe.
    */
  private def assertNeverLoses(game: TicTacToe, botId: PlayerId, botMark: Mark): Unit =
    game.winner match {
      case Some(winner) => winner shouldBe botMark // a decided game the bot did not win means it lost
      case None         =>
        if (!game.isDraw)
          if (game.currentPlayer == botMark) {
            val loc = move(game, botId)
            assertNeverLoses(game.play(botMark, loc).toOption.value, botId, botMark)
          } else
            game.legalMoves.foreach(loc =>
              assertNeverLoses(game.play(game.currentPlayer, loc).toOption.value, botId, botMark)
            )
    }

  "TicTacToePolicy" should {
    "take an immediate winning move" in {
      // X owns the top row's first two cells; the win is at (0,2)
      val game = board(X)(
        "XX.",
        "OO.",
        "..."
      )
      move(game, alice) shouldBe Location(0, 2)
    }

    "block an opponent's immediate winning threat" in {
      // O threatens the left column at (2,0); X has no win of its own, so it must block
      val game = board(X)(
        "OX.",
        "O..",
        "..."
      )
      move(game, alice) shouldBe Location(2, 0)
    }

    "prefer winning now over blocking" in {
      // X can win at (0,2); O threatens the middle row at (1,1), but the win takes priority
      val game = board(X)(
        "XX.",
        "O.O",
        "..."
      )
      move(game, alice) shouldBe Location(0, 2)
    }

    "never lose playing first (X) against any opponent line" in
      assertNeverLoses(TicTacToe.empty(alice, bob), alice, X)

    "never lose playing second (O) against any opponent line" in
      assertNeverLoses(TicTacToe.empty(alice, bob), bob, O)

    "decide nothing for a waiting opponent, a spectator, or a finished game" in {
      val game = TicTacToe.empty(alice, bob) // X (alice) to act
      TicTacToePolicy.decide(TicTacToeModule.project(game, Some(bob)), rng) shouldBe None
      TicTacToePolicy.decide(TicTacToeModule.project(game, None), rng) shouldBe None

      val won = game.copy(winner = Some(X))
      TicTacToePolicy.decide(TicTacToeModule.project(won, Some(alice)), rng) shouldBe None
    }

    "replay identically under the same seed" in {
      val view = TicTacToeModule.project(TicTacToe.empty(alice, bob), Some(alice))
      TicTacToePolicy.decide(view, new Random(7)) shouldBe TicTacToePolicy.decide(view, new Random(7))
    }

    "delegate a non-3×3 grid view to the random baseline" in {
      // ConnectFour also projects to a GridGameView, but on a 6×7 board; the policy must not run its 3×3 search on
      // it and instead hand off to RandomPolicy, which yields a legal move (a ConnectFour drop) for that game.
      val view = ConnectFourModule.project(ConnectFour.empty(alice, bob), Some(alice))
      val decided = TicTacToePolicy.decide(view, new Random(3))
      decided.value shouldBe a[MovePayload.ConnectFourMove]
      decided shouldBe RandomPolicy.decide(view, new Random(3)) // identical to the baseline under the same seed
    }
  }

  "BotPolicies" should {
    "resolve Tic-Tac-Toe at Hard to the optimal policy, and other games to the random baseline" in {
      BotPolicies.forGame(GameType.TicTacToe, BotDifficulty.Hard) shouldBe TicTacToePolicy
      BotPolicies.forGame(GameType.ConnectFour, BotDifficulty.Hard) shouldBe RandomPolicy
      BotPolicies.forGame(GameType.TicTacToe, BotDifficulty.Standard) shouldBe RandomPolicy
    }
  }
}
