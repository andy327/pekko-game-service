package com.andy327.persistence.db

import java.time.Instant

import cats.effect.IO

import io.circe.Json

import com.andy327.model.core.{GameId, PlayerId}

/** A single recorded move in a game's history.
  *
  * @param seq the move's ordinal within the game (0-based, gap-free), assigned by the game actor
  * @param playerId the player who made the move
  * @param move the move payload exactly as it crossed the wire, stored opaquely (not interpreted by this layer)
  * @param createdAt server timestamp when the move was persisted
  */
final case class MoveRecord(seq: Int, playerId: PlayerId, move: Json, createdAt: Instant)

/** Repository for the append-only log of moves played in each game.
  *
  * Distinct from [[GameRepository]], which stores a single overwritten snapshot of current state: this records the full
  * ordered sequence of moves ("how the game got there") for history, replay, and audit. The log is purely additive — it
  * is never read to restore in-memory game state, only on demand via the history endpoint.
  *
  * Moves are stored opaquely as JSON; this layer never deserializes them into game-specific move types, keeping it
  * game-agnostic. Derived facts (whose turn, resulting board, which move won) are intentionally not stored — they are
  * recomputed by folding the move sequence.
  */
trait MoveHistoryRepository {

  /** Runs any needed initialization, such as creating the move-history table. */
  def initialize(): IO[Unit]

  /** Append a single move to `gameId`'s log at ordinal `seq`. Fire-and-forget from the caller's perspective. */
  def appendMove(gameId: GameId, seq: Int, playerId: PlayerId, move: Json): IO[Unit]

  /** Load all recorded moves for `gameId`, ordered by ascending `seq`. Empty if the game has no recorded moves. */
  def loadMoves(gameId: GameId): IO[List[MoveRecord]]
}
