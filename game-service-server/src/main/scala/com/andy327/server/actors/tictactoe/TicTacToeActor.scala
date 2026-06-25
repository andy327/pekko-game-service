package com.andy327.server.actors.tictactoe

import io.circe.generic.semiauto.deriveEncoder

import com.andy327.model.tictactoe.{Location, Mark, TicTacToe}
import com.andy327.server.actors.core.TurnBasedGameActor
import com.andy327.server.game.GridGameState

/** [[com.andy327.server.actors.core.GameActor]] binding for TicTacToe.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (3×3 board, win/draw detection, turn order) live in the
  * `model.tictactoe.TicTacToe` model. X is seated first, O second.
  */
object TicTacToeActor
    extends TurnBasedGameActor[TicTacToe, Location, Mark, GridGameState](
      players => TicTacToe.empty(players(0), players(1)),
      deriveEncoder[Location]
    )
