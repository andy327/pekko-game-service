package com.andy327.actor.bot

import scala.util.Random

import com.andy327.actor.game.{GameView, MovePayload}

/** A bot's decision function: given the bot's own view of a game, choose the move to submit.
  *
  * A policy is a pure function — no actors, no effects, no global randomness. `rng` is a parameter, so a seeded
  * instance replays identically and a test can pin exact choices. The view is the same per-viewer projection a human
  * client receives, redacted at projection time, so a policy is structurally unable to read hidden state; and a view
  * carries only its own viewer's legal moves, so `None` doubles as "nothing to play" — another seat's turn, a
  * spectator's view, or a finished game.
  *
  * The returned payload takes the same path a human move does: it is submitted to the game operation endpoint, where
  * the game's module validates it and injects any server-side randomness (Pig's die, Hold 'Em's deck, Liar's Dice's
  * re-roll pool) before the model applies it.
  */
trait AiPolicy {

  /** The move to submit for the viewer `view` was projected for, or `None` when that viewer has nothing to play. */
  def decide(view: GameView, rng: Random): Option[MovePayload]
}
