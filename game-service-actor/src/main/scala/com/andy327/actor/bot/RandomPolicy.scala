package com.andy327.actor.bot

import scala.util.Random

import com.andy327.actor.game.{
  BattleshipView,
  CheckersView,
  GameView,
  GridGameView,
  HoldEmView,
  LiarsDiceView,
  MastermindView,
  MovePayload,
  PigView
}
import com.andy327.model.mastermind.{Codebreaker, Codemaker, Mastermind}

/** The uniformly random baseline policy: every legal move is equally likely.
  *
  * For the games whose view lists its moves, this picks one of `legalMoves`. Mastermind's move space is described
  * rather than listed, so a random code is drawn from the palette — for the code-setting and every guess alike — and
  * Texas Hold 'Em's bet sizings form a range, so one uniformly drawn total stands alongside the listed chip-free
  * actions as a single equally weighted candidate.
  *
  * This is the floor every game type starts from: legal, deterministic under a seeded `rng`, and indifferent to
  * strategy.
  */
object RandomPolicy extends AiPolicy {

  override def decide(view: GameView, rng: Random): Option[MovePayload] = view match {
    case v: GridGameView   => pick(v.legalMoves, rng)
    case v: CheckersView   => pick(v.legalMoves, rng)
    case v: BattleshipView => pick(v.legalMoves, rng)
    case v: PigView        => pick(v.legalMoves, rng)
    case v: LiarsDiceView  => pick(v.legalMoves, rng)
    case v: MastermindView => mastermind(v, rng)
    case v: HoldEmView     => holdEm(v, rng)
  }

  private def pick[A](moves: List[A], rng: Random): Option[A] =
    if (moves.isEmpty) None else Some(moves(rng.nextInt(moves.size)))

  /** A random code from the palette, played under the action the viewer's role names, on that role's turn only. */
  private def mastermind(view: MastermindView, rng: Random): Option[MovePayload] =
    if (view.viewerRole.contains(view.currentPlayer) && view.winner.isEmpty) {
      val pegs = Mastermind.randomCode(rng).map(_.name).toList
      val action = view.currentPlayer match {
        case Codemaker   => "setcode"
        case Codebreaker => "guess"
      }
      Some(MovePayload.MastermindAction(action, pegs))
    } else None

  /** One of the chip-free actions, or — when a sizing is open — a bet or raise to a uniformly drawn total. */
  private def holdEm(view: HoldEmView, rng: Random): Option[MovePayload] = {
    val sized = view.betSizing.map { sizing =>
      MovePayload.HoldEmAction(sizing.action, Some(sizing.min + rng.nextInt(sizing.max - sizing.min + 1)))
    }
    pick(view.legalMoves ++ sized, rng)
  }
}
