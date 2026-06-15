package com.andy327.server.http.routes

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}

import io.circe.Decoder
import io.circe.parser.decode
import org.apache.pekko.actor.ClassicActorSystemProvider
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{
  ChatHistory,
  Command,
  ErrorResponse,
  GameNotFound,
  GameResponse,
  GameStatus,
  MoveHistory,
  MoveRejected,
  SubscribeAcknowledged
}
import com.andy327.server.game.{GameOperation, GameRegistry}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.routes.RouteDirectives._

/** HTTP routes for managing a specific type of game.
  *
  * This class defines endpoints for submitting player moves and retrieving game status for a given `GameType`. Requests
  * are authenticated using JWT tokens and forwarded to the `GameManager` actor, which routes messages to the
  * appropriate game actor instance.
  *
  * The route prefix is determined by the lowercase name of the game type (e.g., `tictactoe`). JSON payloads for moves
  * are dynamically decoded using the game-specific `MovePayload` decoder from the `GameRegistry`.
  *
  * Route Summary:
  *   - POST /{gameType}/{gameId}/move - Submit a move to the specified game
  *   - GET /{gameType}/{gameId}/status - Fetch the current state of a game
  *   - GET /{gameType}/{gameId}/history - Fetch the ordered move history for a game
  *   - GET /{gameType}/{gameId}/chat - Fetch the recent chat history (backscroll) for a game
  */
class GameRoutes(gameType: GameType, system: ActorSystem[Command]) {
  implicit val scheduler: Scheduler = system.scheduler
  implicit val timeout: Timeout = 3.seconds
  implicit val ec: ExecutionContext = system.executionContext
  implicit val classic: ClassicActorSystemProvider = system.classicSystem

  private val gameTypePrefix = gameType.toString.toLowerCase

  /** Retrieves the game-specific module for the given `gameType` from the `GameRegistry`.
    *
    * This module provides typeclass instances (e.g., decoders) and logic specific to the game, and is required for
    * decoding move payloads and interacting with the game actor.
    */
  private val module = GameRegistry.forType(gameType).module

  /** Parses and decodes a JSON payload from an HTTP request using the provided Circe decoder.
    *
    * This function is useful when the type to decode is not known at compile time and must be provided dynamically
    * (e.g., game-specific `MovePayload` types). It converts the request entity to a strict (fully buffered) form with a
    * timeout, interprets it as a UTF-8 string, and applies the given Circe `Decoder[T]` to parse it.
    *
    * @param decoder Circe decoder for the expected type `T`
    * @tparam T The type to decode from the request body
    * @return A `HttpRequest => Future[Either[String, T]]` function that returns the parse result or error message
    */
  private def decodeJsonEntity[T](decoder: Decoder[T]): HttpRequest => Future[Either[String, T]] = { req =>
    req.entity.toStrict(3.seconds).map { strict =>
      decode[T](strict.data.utf8String)(decoder).left.map(_.getMessage)
    }
  }

  val routes: Route = pathPrefix(gameTypePrefix) {

    pathPrefix(Segment) { gameIdStr =>
      parseGameId(gameIdStr) { gameId =>
        /** Submits a move to the specified game using the authenticated player's ID.
          *
          * The move payload format is game-specific and decoded dynamically via the `GameRegistry`.
          *
          * - Auth: Bearer token required
          * - Path: `gameId` — the UUID of the active game
          * - Body: game-specific `MovePayload` JSON (structure depends on game type)
          * - 200: updated game state after the move is applied
          * - 400: invalid UUID format, or malformed/invalid JSON payload
          * - 401: missing or invalid token
          * - 404: game not found
          * - 409: move rejected (not your turn, illegal move, or game already over)
          * - 500: unexpected error
          */
        path("move") {
          authenticatePlayer { player =>
            post {
              extractRequest { req =>
                onSuccess(decodeJsonEntity(module.moveDecoder)(req)) {
                  case Right(move) =>
                    val op = GameOperation.MakeMove(player.id, move)
                    onSuccess(system.ask[GameResponse](GameManager.RunGameOperation(gameId, op, _))) {
                      case GameStatus(state)  => complete(state)
                      case GameNotFound(id)   => complete(StatusCodes.NotFound -> s"No game found with gameId $id")
                      case MoveRejected(msg)  => complete(StatusCodes.Conflict -> msg)
                      case ErrorResponse(msg) => complete(StatusCodes.InternalServerError -> msg)
                      case unknown => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
                    }

                  case Left(decodingError) =>
                    complete(StatusCodes.BadRequest -> s"Invalid JSON: $decodingError")
                }
              }
            }
          }
        } ~
        /** Fetches the current state of the specified game.
          *
          * - Path: `gameId` — the UUID of the game to check
          * - 200: current game state
          * - 400: invalid UUID format
          * - 404: game not found
          * - 500: unexpected error
          */
        path("status") {
          get {
            onSuccess(system.ask[GameResponse](GameManager.RunGameOperation(gameId, GameOperation.GetState, _))) {
              case GameStatus(state)  => complete(state)
              case GameNotFound(id)   => complete(StatusCodes.NotFound -> s"No game found with gameId $id")
              case MoveRejected(msg)  => complete(StatusCodes.Conflict -> msg)
              case ErrorResponse(msg) => complete(StatusCodes.InternalServerError -> msg)
              case unknown            => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
            }
          }
        } ~
        /** Fetches the ordered move history for the specified game.
          *
          * Served from the move log, so it works for both active and finished games (and returns an empty list for a
          * game with no recorded moves).
          *
          * - Path: `gameId` — the UUID of the game
          * - 200: `MoveHistory` — the ordered list of moves played
          * - 400: invalid UUID format
          * - 500: unexpected error
          */
        path("history") {
          get {
            onSuccess(system.ask[GameResponse](GameManager.GetMoveHistory(gameId, _))) {
              case history: MoveHistory => complete(history)
              case ErrorResponse(msg)   => complete(StatusCodes.InternalServerError -> msg)
              case unknown              => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
            }
          }
        } ~
        /** Fetches the recent chat history (backscroll) for the specified game.
          *
          * Served from the chat store, so it works for both active and finished games (and returns an empty list for a
          * game with no recorded messages). Live chat continues to arrive over the WebSocket.
          *
          * - Path: `gameId` — the UUID of the game
          * - 200: `ChatHistory` — the recent messages, oldest first
          * - 400: invalid UUID format
          * - 500: unexpected error
          */
        path("chat") {
          get {
            onSuccess(system.ask[GameResponse](GameManager.GetChatHistory(gameId, _))) {
              case history: ChatHistory => complete(history)
              case ErrorResponse(msg)   => complete(StatusCodes.InternalServerError -> msg)
              case unknown              => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
            }
          }
        } ~
        /** Subscribes the authenticated player to push events for the specified active game.
          *
          * The player must have an active WebSocket connection established before calling this endpoint.
          *
          * - Auth: Bearer token required
          * - Path: `gameId` — the UUID of the active game to observe
          * - 200: `SubscribeAcknowledged` confirming the subscription was registered
          * - 400: invalid UUID format, player has no active WebSocket connection, or game is not active
          * - 401: missing or invalid token
          * - 500: unexpected error
          */
        path("subscribe") {
          post {
            authenticatePlayer { player =>
              onSuccess(system.ask[GameResponse](GameManager.SubscribePlayerToGame(gameId, player.id, _))) {
                case ack: SubscribeAcknowledged => complete(ack)
                case ErrorResponse(msg)         => complete(StatusCodes.BadRequest -> msg)
                case unknown => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
              }
            }
          }
        }
      }
    }
  }
}
