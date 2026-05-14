package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{
  GameResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyErrorResponse,
  LobbyInfo,
  LobbyJoined,
  LobbyLeft
}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.routes.RouteDirectives._
import com.andy327.server.lobby.LobbyError

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

  private def statusFor(error: LobbyError): StatusCode = error match {
    case _: LobbyError.LobbyNotFound => StatusCodes.NotFound
    case _: LobbyError.NotHostError  => StatusCodes.Forbidden
    case _                           => StatusCodes.Conflict
  }

  val routes: Route = pathPrefix("lobby") {

    /**
     * Route: GET /lobby/list
     * Response: 200 List of LobbyMetadata objects representing all open lobbies
     * Response: 500 If an unexpected error occurs while retrieving lobby list
     *
     * Lists metadata for all open lobbies.
     */
    path("list") {
      get {
        parameters("gameType".?, "page".as[Int].withDefault(1), "limit".as[Int].withDefault(20)) {
          (gameTypeStr, page, limit) =>
            val gameTypeResult: Either[String, Option[GameType]] = gameTypeStr match {
              case None    => Right(None)
              case Some(s) => GameType.fromString(s).toRight(s"Unknown game type: $s").map(Some(_))
            }
            gameTypeResult match {
              case Left(err)                            => complete(StatusCodes.BadRequest -> err)
              case Right(_) if page < 1                 => complete(StatusCodes.BadRequest -> "page must be >= 1")
              case Right(_) if limit < 1 || limit > 100 =>
                complete(StatusCodes.BadRequest -> "limit must be between 1 and 100")
              case Right(gameTypeFilter) =>
                onSuccess(system.ask[GameResponse](GameManager.ListLobbies(gameTypeFilter, page, limit, _))) {
                  case listed: LobbiesListed => complete(listed)
                  case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
                }
            }
        }
      }
    } ~
    /**
     * Route: POST /lobby/create/{gameType}
     * Path param: gameType The type of game to create a lobby for (e.g., "tictactoe")
     * Auth: Requires Bearer token
     * Response: 200 LobbyCreated with the new game ID and host player info
     * Response: 400 If the provided game type is invalid
     * Response: 500 If an unexpected error occurs while creating the lobby
     *
     * Creates a new game lobby for the specified game type using the authenticated player.
     */
    path("create" / Segment) { gameTypeStr =>
      parseGameType(gameTypeStr) { gameType =>
        post {
          authenticatePlayer { player =>
            onSuccess(system.ask[GameResponse](replyTo => GameManager.CreateLobby(gameType, player, replyTo))) {
              case created: LobbyCreated => complete(created)
              case other                 => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
            }
          }
        }
      }
    } ~
    pathPrefix(Segment) { gameIdStr =>
      parseGameId(gameIdStr) { gameId =>
        /**
         * Route: GET /lobby/{gameId}
         * Path param: gameId The ID of the lobby to retrieve
         * Response: 200 LobbyMetadata if found
         * Response: 404 If the specified lobby does not exist
         * Response: 500 If an unexpected error occurs while retrieving metadata
         *
         * Fetches metadata for a specific lobby.
         */
        pathEndOrSingleSlash {
          get {
            onSuccess(system.ask[GameResponse](replyTo => GameManager.GetLobbyInfo(gameId, replyTo))) {
              case LobbyInfo(metadata)       => complete(metadata)
              case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
              case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
            }
          }
        } ~
        /**
         * Route: POST /lobby/{gameId}/join
         * Auth: Requires Bearer token
         * Path param: gameId The ID of the lobby to join
         * Response: 200 LobbyJoined with metadata for the joined lobby
         * Response: 404 If the specified lobby does not exist
         * Response: 409 If the join request conflicts (already joined, lobby full, game started)
         * Response: 500 If an unexpected error occurs while joining the lobby
         *
         * Joins an existing lobby using the authenticated player.
         */
        path("join") {
          post {
            authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.JoinLobby(gameId, player, replyTo))) {
                case joined: LobbyJoined       => complete(joined)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        } ~
        /**
         * Route: POST /lobby/{gameId}/leave
         * Auth: Requires Bearer token
         * Path param: gameId The ID of the lobby to leave
         * Response: 200 LobbyLeft with gameId and message
         * Response: 404 If the specified lobby does not exist
         * Response: 500 If an unexpected error occurs while leaving the lobby
         *
         * Leaves an existing lobby using the authenticated player.
         */
        path("leave") {
          post {
            authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.LeaveLobby(gameId, player, replyTo))) {
                case left @ LobbyLeft(_, _)    => complete(left)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        } ~
        /**
         * Route: POST /lobby/{gameId}/start
         * Auth: Requires Bearer token
         * Path param: gameId The ID of the lobby to start
         * Response: 200 GameStarted with the new game ID
         * Response: 403 If the requester is not the host
         * Response: 404 If the lobby does not exist
         * Response: 409 If the lobby does not have enough players to start
         * Response: 500 If an unexpected error occurs while starting the game
         *
         * Starts the game from the given lobby. Must be called by the host.
         */
        path("start") {
          post {
            authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.StartGame(gameId, player.id, replyTo))) {
                case gs @ GameStarted(_)       => complete(gs)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        }
      }
    }
  }
}
