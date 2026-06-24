package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{GameResponse, PlayerSessions}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.auth.{ActiveGameSummary, PlayerSessionsResponse}
import com.andy327.server.http.json.JsonProtocol._

/** HTTP routes for a player's own live participation across the service.
  *
  * Where [[com.andy327.server.http.routes.AuthRoutes]] serves a player's durable identity and completed-game history,
  * these routes answer "what am I in right now?" — the pre-game lobbies and in-progress games the authenticated player
  * is currently part of. The view is derived from the authoritative actor state ([[server.actors.core.GameManager]] and
  * its [[com.andy327.server.actors.core.LobbyManager]] child), not a separate store, so it cannot drift from live
  * state.
  *
  * Route Summary:
  *   - GET /players/me/sessions - Return the authenticated player's joined lobbies and active games
  */
class PlayerRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("players" / "me") {

    /** Returns the authenticated player's current participation — joined pre-game lobbies and in-progress games.
      *
      * The view is keyed to the account the token identifies, so a caller only ever sees their own sessions. It is
      * strictly live state; completed games are served by `GET /auth/me/history` instead.
      *
      * - Auth: Bearer token required (identifies the player)
      * - 200: `PlayerSessionsResponse` — `lobbies` and `games` (either may be empty)
      * - 401: missing, invalid, or expired token
      * - 500: unexpected error
      */
    path("sessions") {
      get {
        authenticatePlayer { player =>
          onSuccess(system.ask[GameResponse](GameManager.GetPlayerSessions(player.id, _))) {
            case PlayerSessions(lobbies, games) =>
              complete(
                PlayerSessionsResponse(
                  lobbies,
                  games.map { case (id, gameType) =>
                    ActiveGameSummary(id, gameType)
                  }
                )
              )
            case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
          }
        }
      }
    }
  }
}
