package com.andy327.server.http.auth

import com.andy327.model.core.{GameId, GameType}
import com.andy327.server.lobby.LobbyMetadata

/** One live game the requesting player is seated in, as returned by `GET /players/me/sessions`.
  *
  * Deliberately minimal — just enough to re-discover and reconnect to the match. The full game state is fetched
  * separately via the game endpoint.
  *
  * @param gameId the in-progress game
  * @param gameType the kind of game being played
  */
case class ActiveGameSummary(gameId: GameId, gameType: GameType)

/** Body of `GET /players/me/sessions` — the authenticated player's current participation.
  *
  * This is strictly live state: pre-game `lobbies` the player has joined and in-progress `games` they are seated in.
  * Completed games are not included here; those are served by the player-history endpoint.
  *
  * @param lobbies pre-game lobbies (waiting or ready to start) the player has joined
  * @param games in-progress games the player is participating in
  */
case class PlayerSessionsResponse(lobbies: List[LobbyMetadata], games: List[ActiveGameSummary])
