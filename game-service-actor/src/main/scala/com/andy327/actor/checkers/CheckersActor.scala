package com.andy327.actor.checkers

import io.circe.syntax._
import io.circe.{Encoder, Json}

import com.andy327.actor.core.TurnBasedGameActor
import com.andy327.actor.game.GridGameState
import com.andy327.model.checkers.{Checkers, Color, Move, Square}

/** [[core.GameActor]] binding for Checkers.
  *
  * All behavior lives in [[core.TurnBasedGameActor]]; the rules (8×8 dark squares, mandatory captures, multi-jumps,
  * crowning, stalemate detection) live in `model.checkers.Checkers`. Red is seated first and moves first, Black second.
  *
  * The move-log encoder records the move's `from` square and its ordered landing squares, matching the `{from, steps}`
  * shape the HTTP move endpoint accepts.
  */
object CheckersActor
    extends TurnBasedGameActor[Checkers, Move, Color, GridGameState](
      players => Checkers.empty(players(0), players(1)),
      Encoder.instance[Move] { move =>
        def square(sq: Square): Json = Json.obj("row" -> sq.row.asJson, "col" -> sq.col.asJson)
        Json.obj("from" -> square(move.from), "steps" -> move.steps.map(square).asJson)
      }
    )
