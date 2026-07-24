package com.andy327.actor.bot

import java.util.UUID

import scala.util.Random

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.game.modules.{
  BattleshipModule,
  CheckersModule,
  ConnectFourModule,
  GameModule,
  LiarsDiceModule,
  MastermindModule,
  PigModule,
  TexasHoldEmModule,
  TicTacToeModule
}
import com.andy327.actor.game.{GameOperation, GameView, MovePayload}
import com.andy327.model.battleship.Battleship
import com.andy327.model.checkers.{Black, Checkers, Piece, Red, Square}
import com.andy327.model.core.{Game, GameError, GameType, PlayerId}
import com.andy327.model.holdem.{Card, TexasHoldEm}
import com.andy327.model.liarsdice.{Bid, LiarsDice, StandingBid}
import com.andy327.model.mastermind.{Mastermind, Peg}
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.TicTacToe

class RandomPolicySpec extends AnyWordSpecLike with Matchers with OptionValues {
  private val testKit = ActorTestKit()
  import testKit._

  private val alice: PlayerId = UUID.randomUUID()
  private val bob: PlayerId = UUID.randomUUID()

  private val seeds = 0 until 20

  /** For every seed: decide from `viewer`'s projection of `game`, then push the payload through the game's module and
    * the model's `play` — the same path a submitted move takes — asserting each decided move is accepted. The
    * widening cast erases the game's move and player types, which is safe here for the same reason it is in
    * `GameModuleBundle`: the module guarantees the command it built carries this game's own move type.
    */
  private def playsOnlyLegalMoves[G <: Game[_, _, _, _, _]](game: G, module: GameModule[G], viewer: PlayerId): Unit = {
    val replyProbe = TestProbe[Either[GameError, GameView]]()
    val playable = game.asInstanceOf[Game[Any, Any, Any, Any, Any]]
    seeds.foreach { seed =>
      val payload = RandomPolicy.decide(module.project(game, Some(viewer)), new Random(seed)).value
      module.toGameCommand(GameOperation.MakeMove(viewer, payload), replyProbe.ref) match {
        case Right(TurnBasedGameActor.MakeMove(_, move, _)) =>
          withClue(s"seed $seed decided $payload: ") {
            playable.play(playable.playerFor(viewer).value, move) shouldBe a[Right[_, _]]
          }
        case other => fail(s"seed $seed decided $payload, which did not convert: $other")
      }
    }
  }

  "RandomPolicy" should {
    "play only legal TicTacToe moves" in
      playsOnlyLegalMoves(TicTacToe.empty(alice, bob), TicTacToeModule, alice)

    "play only legal ConnectFour moves" in
      playsOnlyLegalMoves(com.andy327.model.connectfour.ConnectFour.empty(alice, bob), ConnectFourModule, alice)

    "play only legal Battleship shots" in
      playsOnlyLegalMoves(Battleship.random(alice, bob, new Random(0)), BattleshipModule, alice)

    "play only legal Pig actions" in
      playsOnlyLegalMoves(Pig.newGame(Seq(alice, bob)), PigModule, alice)

    "play only legal Liar's Dice moves against a standing bid" in {
      val game = LiarsDice
        .newGame(Seq(alice, bob), List.fill(LiarsDice.MaxTotalDice)(3))
        .copy(standing = Some(StandingBid(Bid(2, Some(4)), 1)))
      playsOnlyLegalMoves(game, LiarsDiceModule, alice)
    }

    "play only legal Texas Hold 'Em actions" in
      playsOnlyLegalMoves(TexasHoldEm.newGame(Seq(alice, bob), Card.deck), TexasHoldEmModule, alice)

    "set a code and guess codes drawn from the Mastermind palette" in {
      // the codemaker's single move, then the codebreaker's guesses, both range over the same random-code space
      playsOnlyLegalMoves(Mastermind.newGame(Seq(alice, bob)), MastermindModule, alice)
      val set = Mastermind(alice, bob, Some(Vector(Peg.Red, Peg.Blue, Peg.Green, Peg.Yellow)), Nil, None)
      playsOnlyLegalMoves(set, MastermindModule, bob)
    }

    "take the only legal Checkers move when a capture is mandatory" in {
      val blank = Vector.fill(Checkers.Size)(Option.empty[Piece])
      val board = blank.indices.toVector.map {
        case 4 => blank.updated(3, Some(Piece(Black, isKing = false)))
        case 5 => blank.updated(2, Some(Piece(Red, isKing = false)))
        case _ => blank
      }
      val game = Checkers(alice, bob, board, Red, None, moveCount = 0)

      seeds.foreach { seed =>
        RandomPolicy.decide(CheckersModule.project(game, Some(alice)), new Random(seed)).value shouldBe
          MovePayload.CheckersMove(Square(5, 2), List(Square(3, 4))) // the jump is the entire legal move set
      }
    }

    "decide nothing for a waiting opponent, a spectator, or a finished game" in {
      val game = TicTacToe.empty(alice, bob) // X (alice) to act
      RandomPolicy.decide(TicTacToeModule.project(game, Some(bob)), new Random(0)) shouldBe None
      RandomPolicy.decide(TicTacToeModule.project(game, None), new Random(0)) shouldBe None

      val won = game.copy(winner = Some(com.andy327.model.tictactoe.X))
      RandomPolicy.decide(TicTacToeModule.project(won, Some(alice)), new Random(0)) shouldBe None
    }

    "decide nothing for the Mastermind role not on turn, a spectator, or a finished game" in {
      val unset = Mastermind.newGame(Seq(alice, bob)) // the codemaker (alice) is on turn
      RandomPolicy.decide(MastermindModule.project(unset, Some(bob)), new Random(0)) shouldBe None
      RandomPolicy.decide(MastermindModule.project(unset, None), new Random(0)) shouldBe None

      val finished = Mastermind(
        alice,
        bob,
        Some(Vector(Peg.Red, Peg.Blue, Peg.Green, Peg.Yellow)),
        Nil,
        winner = Some(com.andy327.model.mastermind.Codemaker)
      )
      RandomPolicy.decide(MastermindModule.project(finished, Some(bob)), new Random(0)) shouldBe None
    }

    "keep a Hold 'Em sizing inside the open range" in {
      val game = TexasHoldEm.newGame(Seq(alice, bob), Card.deck) // seat 0 owes the blind: raise range is open
      val view = TexasHoldEmModule.project(game, Some(alice))
      seeds.foreach { seed =>
        RandomPolicy.decide(view, new Random(seed)).value match {
          case MovePayload.HoldEmAction(action, Some(total)) =>
            action shouldBe "raise"
            total should ((be >= 20).and(be <= 1000)) // min-raise up to the all-in, from betBounds
          case MovePayload.HoldEmAction(action, None) => action should (be("fold").or(be("call")))
          case other                                  => fail(s"unexpected Hold 'Em payload: $other")
        }
      }
    }

    "replay identically under the same seed" in {
      val view = TicTacToeModule.project(TicTacToe.empty(alice, bob), Some(alice))
      RandomPolicy.decide(view, new Random(7)) shouldBe RandomPolicy.decide(view, new Random(7))
    }

    "vary its choice across seeds" in {
      val view = TicTacToeModule.project(TicTacToe.empty(alice, bob), Some(alice))
      seeds.map(seed => RandomPolicy.decide(view, new Random(seed)).value).distinct.size should be > 1
    }
  }

  "BotPolicies" should {
    "resolve every game type to the random baseline at Standard difficulty" in {
      val allTypes = List(
        GameType.TicTacToe,
        GameType.ConnectFour,
        GameType.Battleship,
        GameType.Pig,
        GameType.Mastermind,
        GameType.LiarsDice,
        GameType.TexasHoldEm,
        GameType.Checkers
      )
      allTypes.foreach(gameType => BotPolicies.forGame(gameType, BotDifficulty.Standard) shouldBe RandomPolicy)
      // every advertised rung resolves for every game, so a caller can ask for any level without a lookup failure
      allTypes.foreach(gameType =>
        BotDifficulty.all.foreach(level => BotPolicies.forGame(gameType, level) should not be null)
      )
      BotPolicies.forGame(GameType.Pig) shouldBe RandomPolicy // Standard is the default difficulty
    }
  }
}
