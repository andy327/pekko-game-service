package com.andy327.actor.pig

import io.circe.Encoder
import io.circe.syntax._

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.game.PigState
import com.andy327.model.pig.{Hold, Pig, PigMove, Roll}

/** [[core.GameActor]] binding for Pig.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]. The die is rolled by [[game.modules.PigModule]] before the move
  * reaches the model, so `Pig.play` remains a pure function.
  */
object PigActor
    extends TurnBasedGameActor[Pig, PigMove, Int, PigState](
      players => Pig.newGame(players),
      Encoder.instance[PigMove] {
        case Roll(result) => Map("action" -> "roll", "result" -> result.toString).asJson
        case Hold         => Map("action" -> "hold").asJson
      }
    )
