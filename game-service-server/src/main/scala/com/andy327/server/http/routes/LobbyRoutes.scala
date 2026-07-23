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
import com.andy327.server.http.model.ErrorResponse
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
  *   - POST /lobby/{roomId}/bots - Seat a bot in a pre-game lobby (host only)
  *   - DELETE /lobby/{roomId}/bots/{botId} - Remove a bot seat (host only)
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
    case _: LobbyError.NoSuchBot     => StatusCodes.NotFound
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
              case Left(err)            => complete(StatusCodes.BadRequest -> ErrorResponse(err))
              case Right(_) if page < 1 => complete(StatusCodes.BadRequest -> ErrorResponse("page must be >= 1"))
              case Right(_) if limit < 1 || limit > 100 =>
                complete(StatusCodes.BadRequest -> ErrorResponse("limit must be between 1 and 100"))
              case Right(gameTypeFilter) =>
                onSuccess(system.ask[GameResponse](GameManager.ListLobbies(gameTypeFilter, page, limit, _))) {
                  case listed: LobbiesListed => complete(listed)
                  case other                 =>
                    complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
                }
            }
        }
      }
    } ~
    /** Creates a new game lobby for the specified game type using the authenticated player as host.
      *
      * An optional `name` query parameter sets a host-chosen display label for the lobby. It is trimmed, and a blank
      * name is treated as absent; names longer than [[LobbyRoutes.MaxNameLength]] characters are rejected.
      *
      * - Auth: Bearer token required
      * - Path: `gameType` — the type of game to create (e.g., `tictactoe`)
      * - Query `name` (optional): display label, e.g. `Friday night poker`
      * - 200: `LobbyCreated` with the new game ID and host player info
      * - 400: invalid game type, or lobby name too long
      * - 401: missing or invalid token
      * - 500: unexpected error
      */
    path("create" / Segment) { gameTypeStr =>
      parseGameType(gameTypeStr) { gameType =>
        post {
          parameter("name".?) { nameParam =>
            authenticator.authenticatePlayer { player =>
              LobbyRoutes.normalizeName(nameParam) match {
                case Left(err)   => complete(StatusCodes.BadRequest -> ErrorResponse(err))
                case Right(name) =>
                  onSuccess(
                    system.ask[GameResponse](replyTo => GameManager.CreateLobby(gameType, player, name, replyTo))
                  ) {
                    case created: LobbyCreated => complete(created)
                    case other                 =>
                      complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
                  }
              }
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
              case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
              case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
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
                case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
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
                case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
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
                case MoveRejected(msg)         => complete(StatusCodes.Conflict -> ErrorResponse(msg))
                case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
              }
            }
          }
        } ~
        /** Seats a bot in the lobby. Only the host may add bots; the bot counts toward the roster like any player.
          *
          * - Auth: Bearer token required
          * - Path: `roomId` — the UUID of the lobby to seat a bot in
          * - 200: `LobbyJoined` with the seated bot and updated metadata
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 403: requester is not the host
          * - 404: lobby not found
          * - 409: lobby full, or the game has already started
          * - 500: unexpected error
          */
        path("bots") {
          post {
            authenticator.authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.AddBot(roomId, player.id, replyTo))) {
                case joined: LobbyJoined       => complete(joined)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
              }
            }
          }
        } ~
        /** Removes a bot seat from the lobby. Only the host may remove bots.
          *
          * - Auth: Bearer token required
          * - Path: `roomId` — the UUID of the lobby; `botId` — the bot's UUID (from the lobby metadata)
          * - 200: `LobbyLeft` confirming the removal
          * - 400: invalid UUID format
          * - 401: missing or invalid token
          * - 403: requester is not the host
          * - 404: lobby not found, or `botId` is not a bot seated in it
          * - 409: the game has already started
          * - 500: unexpected error
          */
        path("bots" / JavaUUID) { botId =>
          delete {
            authenticator.authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.RemoveBot(roomId, player.id, botId, replyTo))) {
                case left @ LobbyLeft(_, _)    => complete(left)
                case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
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
                case LobbyErrorResponse(error) => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
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
                case ack: SubscribeAcknowledged     => complete(ack)
                case GameManager.ErrorResponse(msg) => complete(StatusCodes.BadRequest -> ErrorResponse(msg))
                case LobbyErrorResponse(error)      => complete(statusFor(error) -> ErrorResponse(error.message))
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
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
                case other => complete(StatusCodes.InternalServerError -> ErrorResponse(s"Unexpected response: $other"))
              }
            }
          }
        }
      }
    }
  }
}

object LobbyRoutes {

  /** Maximum allowed length, in characters, of a host-chosen lobby name. */
  val MaxNameLength: Int = 40

  /** Normalizes an optional lobby name: trims surrounding whitespace and treats a blank result as absent (`None`).
    * Returns the cleaned name on the right, or an error message on the left when the trimmed name exceeds
    * [[MaxNameLength]] characters.
    */
  def normalizeName(name: Option[String]): Either[String, Option[String]] =
    name.map(_.trim).filter(_.nonEmpty) match {
      case Some(n) if n.length > MaxNameLength => Left(s"Lobby name must be at most $MaxNameLength characters")
      case cleaned                             => Right(cleaned)
    }
}
