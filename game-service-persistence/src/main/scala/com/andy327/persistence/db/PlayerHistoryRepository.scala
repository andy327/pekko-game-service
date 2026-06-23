package com.andy327.persistence.db

import java.time.Instant

import cats.effect.IO

import com.andy327.model.core.{GameId, GameType, PlayerId}

object PlayerHistoryRepository {

  /** How a completed game turned out for a single participant.
    *
    * This is a per-player outcome, not the game's overall status: the same finished game yields `Win` for one player
    * and `Loss` for the other. A forfeit is recorded as a `Win`/`Loss` here with the `forfeit` flag set separately (see
    * [[PlayerGameRecord]]), keeping this enum about who came out ahead regardless of how.
    */
  sealed trait GameResult {

    /** Stable lower-case label used as the stored column value. */
    def label: String
  }

  object GameResult {
    case object Win extends GameResult { val label = "win" }
    case object Loss extends GameResult { val label = "loss" }
    case object Draw extends GameResult { val label = "draw" }

    val all: List[GameResult] = List(Win, Loss, Draw)

    def fromLabel(s: String): Option[GameResult] = all.find(_.label == s)
  }
}

/** One completed game from a single player's perspective, as returned by [[PlayerHistoryRepository.findByPlayer]]. The
  * player is the query key and so is not repeated here.
  *
  * @param gameId the completed game
  * @param gameType the kind of game played
  * @param result how the game turned out for this player
  * @param forfeit true when the result was reached by a player leaving an in-progress game rather than normal play
  * @param finishedAt server timestamp when the game completed
  */
final case class PlayerGameRecord(
    gameId: GameId,
    gameType: GameType,
    result: PlayerHistoryRepository.GameResult,
    forfeit: Boolean,
    finishedAt: Instant
)

/** Repository for each player's record of completed games — the per-player history that outlives a live session.
  *
  * Where [[GameRepository]] stores current game state and [[MoveHistoryRepository]] stores how a single game unfolded,
  * this stores one row per `(player, game)`: the outcome of every finished game a player took part in, keyed on the
  * player so a returning, authenticated player can be shown their history. Identity is anchored to the `accounts`
  * table (a player id is an account id), but no foreign key is enforced here — the table stays independent of account
  * and game lifecycles, and completed games may be pruned out from under it.
  */
trait PlayerHistoryRepository {

  /** Runs any needed initialization, such as creating the player-history table. */
  def initialize(): IO[Unit]

  /** Record `playerId`'s outcome for a completed `gameId`. One call per participant; fire-and-forget from the caller's
    * perspective. Idempotent on `(playerId, gameId)`, so a re-delivered completion does not duplicate the row.
    */
  def record(
      playerId: PlayerId,
      gameId: GameId,
      gameType: GameType,
      result: PlayerHistoryRepository.GameResult,
      forfeit: Boolean
  ): IO[Unit]

  /** Load all of `playerId`'s completed games, most recently finished first. Empty if the player has no history. */
  def findByPlayer(playerId: PlayerId): IO[List[PlayerGameRecord]]
}
