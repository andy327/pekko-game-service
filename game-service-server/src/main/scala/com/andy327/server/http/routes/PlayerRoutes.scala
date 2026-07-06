package com.andy327.server.http.routes

import scala.concurrent.duration._

import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.actor.core.GameManager
import com.andy327.actor.core.GameManager.{GameResponse, PlayerSessions}
import com.andy327.persistence.db.{InMemoryPlayerHistoryRepository, PlayerHistoryRepository}
import com.andy327.server.http.auth.{JwtAuthenticator, PlayerGameSummary, PlayerHistory}
import com.andy327.server.http.json.JsonProtocol._

/** HTTP routes for a player's own data: their current participation and their completed-game history.
  *
  * Where [[com.andy327.server.http.routes.AuthRoutes]] establishes identity (credentials and tokens), these routes
  * answer questions a player asks about themselves. `sessions` reports "what am I in right now?" — the pre-game lobbies
  * and in-progress games the player is currently part of, derived from the authoritative actor state
  * (`GameManager` and its `LobbyManager` child) so it cannot
  * drift from live state. `history` reports the player's durable record of finished games, read from the
  * `PlayerHistoryRepository`.
  *
  * Route Summary:
  *   - GET /players/me/sessions - Return the authenticated player's joined lobbies and active games
  *   - GET /players/me/history - Return the authenticated player's completed-game history
  */
class PlayerRoutes(
    system: ActorSystem[GameManager.Command],
    playerHistoryRepo: PlayerHistoryRepository = new InMemoryPlayerHistoryRepository,
    authenticator: JwtAuthenticator = new JwtAuthenticator()
)(implicit runtime: IORuntime) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("players" / "me") {

    /** Returns the authenticated player's current participation — joined pre-game lobbies and in-progress games.
      *
      * The view is keyed to the account the token identifies, so a caller only ever sees their own sessions. It is
      * strictly live state; completed games are served by `GET /players/me/history` instead.
      *
      * - Auth: Bearer token required (identifies the player)
      * - 200: `PlayerSessions` — `lobbies` and `games` (either may be empty)
      * - 401: missing, invalid, or expired token
      * - 500: unexpected error
      */
    path("sessions") {
      get {
        authenticator.authenticatePlayer { player =>
          onSuccess(system.ask[GameResponse](GameManager.GetPlayerSessions(player.id, _))) {
            case sessions: PlayerSessions => complete(sessions)
            case other                    => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
          }
        }
      }
    } ~
    /** Returns the authenticated player's completed-game history, most recently finished first.
      *
      * The history is read for the account the token identifies, so a caller only ever sees their own games.
      *
      * - Auth: Bearer token required (identifies the player)
      * - 200: `PlayerHistory` — the player's completed games (empty `games` if none)
      * - 401: missing, invalid, or expired token
      */
    path("history") {
      get {
        authenticator.authenticatePlayer { player =>
          onSuccess(playerHistoryRepo.findByPlayer(player.id).unsafeToFuture()) { records =>
            val games = records.map(r => PlayerGameSummary(r.matchId, r.gameType, r.result, r.forfeit, r.finishedAt))
            complete(PlayerHistory(games))
          }
        }
      }
    }
  }
}
