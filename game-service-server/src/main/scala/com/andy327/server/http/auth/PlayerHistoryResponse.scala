package com.andy327.server.http.auth

import java.time.Instant

import com.andy327.model.core.{GameType, MatchId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

/** One completed game in a player's history, as returned by `GET /players/me/history`.
  *
  * @param matchId the completed match
  * @param gameType the kind of game played
  * @param result how the game turned out for the requesting player
  * @param forfeit true when the result was reached by a player leaving rather than normal play
  * @param finishedAt when the game completed
  */
case class PlayerGameSummary(
    matchId: MatchId,
    gameType: GameType,
    result: GameResult,
    forfeit: Boolean,
    finishedAt: Instant
)

/** Body of `GET /players/me/history` — the authenticated player's completed games, most recently finished first. */
case class PlayerHistory(games: List[PlayerGameSummary])
