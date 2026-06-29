package com.andy327.persistence.db

import java.time.Instant

import cats.effect.IO
import cats.effect.kernel.Ref

import com.andy327.model.core.{GameType, MatchId, PlayerId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

/** A [[PlayerHistoryRepository]] that keeps history in memory, with the same semantics as the Postgres implementation
  * (one row per `(player, game)`, idempotent record, most-recently-finished-first reads).
  *
  * Unlike a NoOp, this actually stores rows so the history read path works end to end — it is the default where
  * Postgres-backed history is not wired (tests, local runs); the real [[postgres.PostgresPlayerHistoryRepository]] is
  * supplied in production.
  */
class InMemoryPlayerHistoryRepository extends PlayerHistoryRepository {

  /** Append-ordered rows; `(playerId, matchId)` uniqueness is enforced in [[record]]. */
  private val rows: Ref[IO, Vector[(PlayerId, PlayerGameRecord)]] = Ref.unsafe(Vector.empty)

  override def initialize(): IO[Unit] = IO.unit

  override def record(
      playerId: PlayerId,
      matchId: MatchId,
      gameType: GameType,
      result: GameResult,
      forfeit: Boolean
  ): IO[Unit] =
    rows.update { current =>
      if (current.exists { case (pid, rec) => pid == playerId && rec.matchId == matchId }) current
      else current :+ (playerId -> PlayerGameRecord(matchId, gameType, result, forfeit, Instant.now()))
    }

  override def findByPlayer(playerId: PlayerId): IO[List[PlayerGameRecord]] =
    rows.get.map { current =>
      current
        .collect { case (pid, rec) if pid == playerId => rec }
        // ascending-stable then reverse yields most-recent-first, with later-recorded winning ties
        .sortBy(_.finishedAt)(Ordering.fromLessThan(_ isBefore _))
        .reverse
        .toList
    }
}
