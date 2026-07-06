package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.actor.core.GameManager
import com.andy327.actor.core.GameManager.{
  ErrorResponse,
  GameForfeited,
  GameResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyErrorResponse,
  LobbyInfo,
  LobbyJoined,
  LobbyLeft,
  MoveRejected,
  SubscribeAcknowledged,
  UnsubscribeAcknowledged
}
import com.andy327.actor.lobby.LobbyError
import com.andy327.model.core.GameType
import com.andy327.server.http.auth.JwtAuthenticator
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.routes.RouteDirectives._

/** LobbyRoutes defines the HTTP routes for interacting with multiplayer game lobbies.
  *
  * These routes handle the creation, joining, and starting of game lobbies, as well as listing open lobbies. All
  * actions that modify lobby state require player authentication.
  *
  * Route Summary:
  *   - POST /lobby/create/{gameType} - Create a new lobby for a specific game type
  *   - GET /lobby/list - List all available open lobbies
  *   - GET /lobby/{roomId} - Fetch metadata for a specific lobby
  *   - POST /lobby/{roomId}/join - Join an existing lobby
  *   - POST /lobby/{roomId}/leave - Leave a lobby (or forfeit an in-progress game)
  *   - POST /lobby/{roomId}/start - Start a game from a lobby (host only)
  *   - DELETE /lobby/{roomId} - Cancel a pre-game lobby (host only)
  *   - POST /lobby/{roomId}/subscribe - Start spectating a lobby (push events over the player's WebSocket)
  *   - DELETE /lobby/{roomId}/subscribe - Stop spectating a lobby
  */
class LobbyRoutes(
    system: ActorSystem[GameManager.Command],
    authenticator: JwtAuthenticator = new JwtAuthenticator()
) {
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
          authenticator.authenticatePlayer { player =>
            onSuccess(system.ask[GameResponse](replyTo => GameManager.CreateLobby(gameType, player, replyTo))) {
              case created: LobbyCreated => complete(created)
              case other                 => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
            }
          }
        }
      }
    } ~
    pathPrefix(Segment) { roomIdStr =>
      parseRoomId(roomIdStr) { roomId =>
        /** Fetches metadata for a specific lobby.
          *
          * - Path: `roomId` — the UUID of the lobby to retrieve
          * - 200: `LobbyMetadata` for the specified lobby
          * - 400: invalid UUID format
          * - 404: lobby not found
          * - 500: unexpected error
          */
        pathEndOrSingleSlash {
          get {
            onSuccess(system.ask[GameResponse](replyTo => GameManager.GetLobbyInfo(roomId, replyTo))) {
              case LobbyInfo(metadata)       => complete(metadata)
              case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
              case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
            }
          } ~
          /** Cancels a pre-game lobby. Only the host may call this; it ends the lobby for everyone (subscribers
            * receive a `GameEnded(Cancelled)` push). This is the explicit counterpart to a host leaving via `/leave`.
            *
            * - Auth: Bearer token required
            * - Path: `roomId` — the UUID of the lobby to cancel
            * - 200: `LobbyLeft` with the game ID and a status message
            * - 400: invalid UUID format
            * - 401: missing or invalid token
            * - 403: requester is not the host
            * - 404: lobby not found
            * - 409: the game has already started (leaving is a forfeit, use `/leave`)
            * - 500: unexpected error
            */
          delete {
            authenticator.authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.CancelLobby(roomId, player.id, replyTo))) {
                case left @ LobbyLeft(_, _)    => complete(left)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        } ~
        /** Joins an existing lobby using the authenticated player.
          *
          * - Auth: Bearer token required
          * - Path: `roomId` — the UUID of the lobby to join
          * - 200: `LobbyJoined` with metadata for the joined lobby
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 404: lobby not found
          * - 409: already joined, lobby full, or game already started
          * - 500: unexpected error
          */
        path("join") {
          post {
            authenticator.authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.JoinLobby(roomId, player, replyTo))) {
                case joined: LobbyJoined       => complete(joined)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        } ~
        /** Leaves an existing lobby using the authenticated player.
          *
          * If the host leaves a pre-game lobby with other members present, the host role migrates to a remaining member
          * and the lobby stays open; it is cancelled only if the host was the last player. To deliberately end a lobby,
          * the host uses `DELETE /lobby/{roomId}` instead.
          *
          * - Auth: Bearer token required
          * - Path: `roomId` — the UUID of the lobby to leave
          * - 200: `LobbyLeft` (pre-game leave) with the game ID and a status message, or the leaver's final game
          *   state if the game was in progress (leaving forfeits it — the opponent wins)
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 404: lobby not found
          * - 409: the caller is not a participant in the in-progress game, or the game type does not support leaving
          * - 500: unexpected error
          */
        path("leave") {
          post {
            authenticator.authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.LeaveLobby(roomId, player, replyTo))) {
                case left @ LobbyLeft(_, _)    => complete(left)
                case GameForfeited(_, state)   => complete(state)
                case MoveRejected(msg)         => complete(StatusCodes.Conflict -> msg)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        } ~
        /** Starts the game from the given lobby. Only the host may call this endpoint.
          *
          * - Auth: Bearer token required
          * - Path: `roomId` — the UUID of the lobby to start
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
            authenticator.authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.StartGame(roomId, player.id, replyTo))) {
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
          * - Path: `roomId` — the UUID of the lobby to observe
          * - 200: `SubscribeAcknowledged` confirming the subscription was registered
          * - 400: invalid UUID format, or player has no active WebSocket connection
          * - 401: missing or invalid token
          * - 404: lobby not found
          * - 409: game has already started; use the game subscribe endpoint instead
          * - 500: unexpected error
          */
        path("subscribe") {
          post {
            authenticator.authenticatePlayer { player =>
              onSuccess(
                system.ask[GameResponse](replyTo => GameManager.SubscribePlayerToLobby(roomId, player.id, replyTo))
              ) {
                case ack: SubscribeAcknowledged => complete(ack)
                case ErrorResponse(msg)         => complete(StatusCodes.BadRequest -> msg)
                case LobbyErrorResponse(error)  => complete(statusFor(error) -> error.message)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          } ~
          /** Stops spectating the specified lobby for the authenticated player.
            *
            * Idempotent — succeeds whether or not the player was subscribed.
            *
            * - Auth: Bearer token required
            * - Path: `roomId` — the UUID of the lobby to stop observing
            * - 200: `UnsubscribeAcknowledged` confirming the subscription was removed
            * - 400: invalid UUID format
            * - 401: missing or invalid token
            * - 500: unexpected error
            */
          delete {
            authenticator.authenticatePlayer { player =>
              onSuccess(
                system.ask[GameResponse](replyTo => GameManager.UnsubscribePlayerFromLobby(roomId, player.id, replyTo))
              ) {
                case ack: UnsubscribeAcknowledged => complete(ack)
                case other => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
              }
            }
          }
        }
      }
    }
  }
}
