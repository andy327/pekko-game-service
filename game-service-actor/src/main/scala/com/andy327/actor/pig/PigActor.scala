package com.andy327.actor.pig

import io.circe.Encoder
import io.circe.syntax._

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.core.TurnBasedGameActor.TimeoutAction
import com.andy327.actor.game.PigView
import com.andy327.model.pig.{Hold, Pig, PigMove, Roll}

/** [[core.GameActor]] binding for Pig.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]. The die is rolled by [[game.modules.PigModule]] before the move
  * reaches the model, so `Pig.play` remains a pure function.
  *
  * On a turn timeout Pig auto-holds — banking the current turn's accumulated points rather than forfeiting — so an
  * idle seat simply passes its turn and the game keeps moving.
  */
object PigActor
    extends TurnBasedGameActor[Pig, PigMove, Int, PigView](
      players => Pig.newGame(players),
      Encoder.instance[PigMove] {
        case Roll(result) => Map("action" -> "roll", "result" -> result.toString).asJson
        case Hold         => Map("action" -> "hold").asJson
      }
    ) {

  override protected def timeoutAction(game: Pig): TimeoutAction[PigMove] = TimeoutAction.AutoMove(Hold)
}
