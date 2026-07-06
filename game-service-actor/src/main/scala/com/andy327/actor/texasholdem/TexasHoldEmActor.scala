package com.andy327.actor.texasholdem

import io.circe.syntax._
import io.circe.{Encoder, Json}

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.core.TurnBasedGameActor.TimeoutAction
import com.andy327.actor.game.HoldEmState
import com.andy327.actor.game.modules.TexasHoldEmModule
import com.andy327.model.holdem.Action.{Bet, Call, Check, Fold, Raise}
import com.andy327.model.holdem.{HoldEmMove, TexasHoldEm}

/** [[core.GameActor]] binding for Texas Hold 'Em.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (betting, streets, side pots, sit-and-go win) live in
  * `model.holdem.TexasHoldEm`, and the per-viewer projection that hides each player's hole cards lives in
  * `GameStateView[TexasHoldEm, HoldEmState]`. The opening deal is shuffled here — server-side — and dealt by the pure
  * model.
  *
  * The move-log encoder records a bet's or raise's amount but omits the deck each move carries: that deck is internal
  * server randomness for the next hand, not meaningful public history.
  *
  * On a turn timeout Hold 'Em plays the soft, never-forfeiting safe action: auto-check when checking is free
  * (`toCall == 0`), otherwise auto-fold. The player only loses the current hand and stays in the sit-and-go; repeated
  * timeouts blind them down until they bust naturally. The auto-fold carries a fresh deck like any other action, since
  * folding can end the hand and deal the next.
  */
object TexasHoldEmActor
    extends TurnBasedGameActor[TexasHoldEm, HoldEmMove, Int, HoldEmState](
      players => TexasHoldEm.newGame(players, TexasHoldEmModule.shuffledDeck()),
      Encoder.instance[HoldEmMove] { move =>
        move.action match {
          case Fold     => Json.obj("action" -> "fold".asJson)
          case Check    => Json.obj("action" -> "check".asJson)
          case Call     => Json.obj("action" -> "call".asJson)
          case Bet(a)   => Json.obj("action" -> "bet".asJson, "amount" -> a.asJson)
          case Raise(a) => Json.obj("action" -> "raise".asJson, "amount" -> a.asJson)
        }
      }
    ) {

  override protected def timeoutAction(game: TexasHoldEm): TimeoutAction[HoldEmMove] = {
    val action = if (game.toCall(game.currentPlayer) == 0) Check else Fold
    TimeoutAction.AutoMove(HoldEmMove(action, TexasHoldEmModule.shuffledDeck()))
  }
}
