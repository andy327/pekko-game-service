package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{
  ErrorResponse,
  GameResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyJoined
}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.routes.RouteDirectives._

/**
 * LobbyRoutes defines the HTTP routes for interacting with multiplayer game lobbies.
 *
 * These routes handle the creation, joining, and starting of game lobbies, as well as listing open lobbies. All actions
 * that modify lobby state require player authentication.
 *
 * Route Summary:
 * - POST   /lobby/create/{gameType}  - Create a new lobby for a specific game type
 * - POST   /lobby/{gameId}/join      - Join an existing lobby
 * - POST   /lobby/{gameId}/start     - Start a game from a lobby (host only)
 * - GET    /lobby/list               - List all available open lobbies
 */
class LobbyRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("lobby") {

    /**
     * @route POST /lobby/create/{gameType}
     * @pathParam gameType The type of game to create a lobby for (e.g., "tictactoe")
     * @auth Requires Bearer token
     * @response 200 LobbyCreated with the new game ID and host player info
     * @response 400 If the provided game type is invalid
     * @response 500 If an unexpected error occurs while creating the lobby
     *
     * Creates a new game lobby for the specified game type using the authenticated player.
     */
    path("create" / Segment) { gameTypeStr =>
      parseGameType(gameTypeStr) { gameType =>
        post {
          authenticatePlayer { player =>
            onSuccess(system.ask[GameResponse](replyTo => GameManager.CreateLobby(gameType, player, replyTo))) {
              case created: LobbyCreated => complete(created)
              case other                 => complete(StatusCodes.InternalServerError -> s"Unexpected: $other")
            }
          }
        }
      }
    } ~
    /**
     * @route POST /lobby/{gameId}/join
     * @auth Requires Bearer token
     * @pathParam gameId The ID of the lobby to join
     * @response 200 LobbyJoined with metadata for the joined lobby
     * @response 400 If the join request is invalid (e.g., already joined, lobby full)
     * @response 404 If the specified lobby does not exist
     * @response 500 If an unexpected error occurs while joining the lobby
     *
     * Joins an existing lobby using the authenticated player.
     */
    path(Segment / "join") { idStr =>
      parseGameId(idStr) { gameId =>
        post {
          authenticatePlayer { player =>
            onSuccess(system.ask[GameResponse](replyTo => GameManager.JoinLobby(gameId, player, replyTo))) {
              case joined: LobbyJoined => complete(joined)
              case ErrorResponse(msg)  => complete(StatusCodes.BadRequest -> msg)
              case other               => complete(StatusCodes.InternalServerError -> s"Unexpected: $other")
            }
          }
        }
      }
    } ~
    /**
     * @route POST /lobby/{gameId}/start
     * @auth Requires Bearer token
     * @pathParam gameId The ID of the lobby to start
     * @response 200 GameStarted with the new game ID
     * @response 400 If the game cannot be started (e.g., not enough players, not host)
     * @response 404 If the lobby does not exist
     * @response 500 If an unexpected error occurs while starting the game
     *
     * Starts the game from the given lobby. Must be called by the host.
     */
    path(Segment / "start") { idStr =>
      parseGameId(idStr) { gameId =>
        post {
          authenticatePlayer { player =>
            onSuccess(system.ask[GameResponse](replyTo => GameManager.StartGame(gameId, player.id, replyTo))) {
              case gs @ GameStarted(_)    => complete(gs)
              case ErrorResponse(message) => complete(StatusCodes.BadRequest -> message)
              case other                  => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
            }
          }
        }
      }
    } ~
    /**
     * @route GET /lobby/list
     * @response 200 List of LobbyMetadata objects representing all open lobbies
     * @response 500 If an unexpected error occurs while retrieving lobby list
     *
     * Lists all open lobbies.
     */
    path("list") {
      get {
        onSuccess(system.ask[GameResponse](GameManager.ListLobbies)) {
          case LobbiesListed(lobbies) => complete(lobbies)
          case other                  => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
        }
      }
    }
  }
}
