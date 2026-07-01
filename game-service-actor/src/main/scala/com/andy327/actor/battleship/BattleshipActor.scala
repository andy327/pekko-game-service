package com.andy327.actor.battleship

import io.circe.Encoder
import io.circe.generic.semiauto.deriveEncoder

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.game.BattleshipState
import com.andy327.model.battleship.{Battleship, Coord, Fire, Seat}

/** [[core.GameActor]] binding for Battleship.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (firing, hit/sink detection, win when a fleet is sunk)
  * live in `model.battleship.Battleship`, and the fog-of-war projection lives in the per-viewer
  * `GameStateView[Battleship, BattleshipState]`. Player1 is seated first; fleets are placed at random.
  */
object BattleshipActor
    extends TurnBasedGameActor[Battleship, Fire, Seat, BattleshipState](
      players => Battleship.random(players(0), players(1)), {
        // Fire nests a Coord, so its encoder must be in scope to derive the move-log encoder
        implicit val coordEncoder: Encoder[Coord] = deriveEncoder[Coord]
        deriveEncoder[Fire]
      }
    )
