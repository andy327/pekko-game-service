package com.andy327.actor.mastermind

import io.circe.syntax._
import io.circe.{Encoder, Json}

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.game.MastermindView
import com.andy327.model.mastermind.{Guess, Mastermind, MastermindMove, Role, SetCode}

/** [[core.GameActor]] binding for Mastermind.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (setting the code, scoring guesses, winning by cracking
  * or exhausting guesses) live in `model.mastermind.Mastermind`, and the per-viewer projection that hides the secret
  * lives in `GameProjection[Mastermind, MastermindView]`. The codemaker (seat 0) sets the code first.
  *
  * The move-log encoder deliberately redacts the code from a `SetCode`: the history log is served publicly via
  * `GET /{gameType}/{roomId}/history`, so logging the pegs would let the codebreaker read the answer mid-game.
  */
object MastermindActor
    extends TurnBasedGameActor[Mastermind, MastermindMove, Role, MastermindView](
      players => Mastermind.newGame(players),
      Encoder.instance[MastermindMove] {
        case SetCode(_)  => Json.obj("action" -> "setcode".asJson) // pegs redacted: never leak the secret to /history
        case Guess(pegs) => Json.obj("action" -> "guess".asJson, "pegs" -> pegs.map(_.name).asJson)
      }
    )
