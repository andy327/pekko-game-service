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
  ErrorResponse,
  GameResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyErrorResponse,
  LobbyInfo,
  LobbyJoined,
  LobbyLeft,
  SubscribeAcknowledged
}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.routes.RouteDirectives._
import com.andy327.server.lobby.LobbyError

/** LobbyRoutes defines the HTTP routes for interacting with multiplayer game lobbies.
  *
  * These routes handle the creation, joining, and starting of game lobbies, as well as listing open lobbies. All
  * actions that modify lobby state require player authentication.
  *
  * Route Summary:
  *   - POST /lobby/create/{gameType} - Create a new lobby for a specific game type
  *   - POST /lobby/{gameId}/join - Join an existing lobby
  *   - POST /lobby/{gameId}/start - Start a game from a lobby (host only)
  *   - GET /lobby/list - List all available open lobbies
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

    /** Lists metadata for all joinable lobbies with optional filtering and pagination.
      *
      * - Query `gameType` (optional): filter by game type (e.g., `tictactoe`)
      * - Query `page` (default 1): page number
      * - Query `limit` (default 20, max 100): results per page
      * - 200: paginated list of `LobbyMetadata` objects
      * - 400: invalid `gameType`, `page`, or `limit` value
      * - 500: unexpected error
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
    /** Creates a new game lobby for the specified game type using the authenticated player as host.
      *
      * - Auth: Bearer token required
      * - Path: `gameType` — the type of game to create (e.g., `tictactoe`)
      * - 200: `LobbyCreated` with the new game ID and host player info
      * - 400: invalid game type
      * - 401: missing or invalid token
      * - 500: unexpected error
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
        /** Fetches metadata for a specific lobby.
          *
          * - Path: `gameId` — the UUID of the lobby to retrieve
          * - 200: `LobbyMetadata` for the specified lobby
          * - 400: invalid UUID format
          * - 404: lobby not found
          * - 500: unexpected error
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
        /** Joins an existing lobby using the authenticated player.
          *
          * - Auth: Bearer token required
          * - Path: `gameId` — the UUID of the lobby to join
          * - 200: `LobbyJoined` with metadata for the joined lobby
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 404: lobby not found
          * - 409: already joined, lobby full, or game already started
          * - 500: unexpected error
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
        /** Leaves an existing lobby using the authenticated player.
          *
          * - Auth: Bearer token required
          * - Path: `gameId` — the UUID of the lobby to leave
          * - 200: `LobbyLeft` with the game ID and a status message
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 404: lobby not found
          * - 500: unexpected error
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
        /** Starts the game from the given lobby. Only the host may call this endpoint.
          *
          * - Auth: Bearer token required
          * - Path: `gameId` — the UUID of the lobby to start
          * - 200: `GameStarted` with the game ID
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 403: requester is not the host
          * - 404: lobby not found
          * - 409: not enough players to start
          * - 500: unexpected error
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
        } ~
        /** Subscribes the authenticated player to push events for the specified lobby.
          *
          * The player must have an active WebSocket connection established before calling this endpoint.
          *
          * - Auth: Bearer token required
          * - Path: `gameId` — the UUID of the lobby to observe
          * - 200: `SubscribeAcknowledged` confirming the subscription was registered
          * - 400: invalid UUID format, or player has no active WebSocket connection
          * - 401: missing or invalid token
          * - 404: lobby not found
          * - 409: game has already started; use the game subscribe endpoint instead
          * - 500: unexpected error
          */
        path("subscribe") {
          post {
            authenticatePlayer { player =>
              onSuccess(
                system.ask[GameResponse](replyTo => GameManager.SubscribePlayerToLobby(gameId, player.id, replyTo))
              ) {
                case ack: SubscribeAcknowledged => complete(ack)
                case ErrorResponse(msg)         => complete(StatusCodes.BadRequest -> msg)
                case LobbyErrorResponse(error)  => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        }
      }
    }
  }
}
