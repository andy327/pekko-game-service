package com.andy327.persistence.db

import cats.effect.IO

import io.circe.Json

import com.andy327.model.core.{MatchId, PlayerId}

/** A [[MoveHistoryRepository]] that records nothing and returns no history.
  *
  * Used as the default where move-history persistence is not wired (e.g. tests that don't exercise the feature); the
  * real [[com.andy327.persistence.db.postgres.PostgresMoveHistoryRepository]] is supplied in production.
  */
object NoOpMoveHistoryRepository extends MoveHistoryRepository {
  override def initialize(): IO[Unit] = IO.unit
  override def appendMove(matchId: MatchId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] = IO.unit
  override def loadMoves(matchId: MatchId): IO[List[MoveRecord]] = IO.pure(Nil)
}
