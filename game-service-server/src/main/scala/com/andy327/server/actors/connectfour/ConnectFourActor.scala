package com.andy327.server.actors.connectfour

import io.circe.generic.semiauto.deriveEncoder

import com.andy327.model.connectfour.{ConnectFour, Drop, Mark}
import com.andy327.server.actors.core.TurnBasedGameActor
import com.andy327.server.game.GridGameState

/** [[com.andy327.server.actors.core.GameActor]] binding for ConnectFour.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (6×7 board, gravity, four-in-a-line detection, turn
  * order) live in the `model.connectfour.ConnectFour` model. Red is seated first and moves first, Yellow second.
  */
object ConnectFourActor
    extends TurnBasedGameActor[ConnectFour, Drop, Mark, GridGameState](
      players => ConnectFour.empty(players(0), players(1)),
      deriveEncoder[Drop]
    )
