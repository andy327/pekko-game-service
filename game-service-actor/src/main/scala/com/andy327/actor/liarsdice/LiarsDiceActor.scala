package com.andy327.actor.liarsdice

import io.circe.syntax._
import io.circe.{Encoder, Json}

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.game.LiarsDiceView
import com.andy327.actor.game.modules.LiarsDiceModule
import com.andy327.model.liarsdice.{Challenge, LiarsDice, LiarsDiceMove, MakeBid}

/** [[core.GameActor]] binding for Liar's Dice.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (bidding-track raises, wild-ones counting, per-round
  * elimination) live in `model.liarsdice.LiarsDice`, and the per-viewer projection that hides each player's dice lives
  * in `GameProjection[LiarsDice, LiarsDiceView]`. The starting hands are rolled here — server-side — and dealt by the
  * pure model, mirroring how Pig supplies its die result.
  *
  * The move-log encoder records a bid's quantity and face but omits a challenge's re-roll pool: that pool is internal
  * server randomness for the next round, not meaningful public history.
  */
object LiarsDiceActor
    extends TurnBasedGameActor[LiarsDice, LiarsDiceMove, Int, LiarsDiceView](
      players => LiarsDice.newGame(players, LiarsDiceModule.rollPool()),
      Encoder.instance[LiarsDiceMove] {
        case MakeBid(bid) =>
          Json.obj("action" -> "bid".asJson, "quantity" -> bid.quantity.asJson, "face" -> bid.face.asJson)
        case Challenge(_) =>
          Json.obj("action" -> "challenge".asJson) // re-roll pool omitted from the public history log
      }
    )
