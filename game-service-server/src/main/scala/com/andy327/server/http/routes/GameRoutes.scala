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
import com.andy327.server.actors.core.GameManager.{Command, ErrorResponse, GameResponse, GameStatus}
import com.andy327.server.game.{GameOperation, GameRegistry}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._

/**
 * HTTP routes for managing a specific type of game.
 *
 * This class defines endpoints for submitting player moves and retrieving game status for a given `GameType`. Requests
 * are authenticated using JWT tokens and forwarded to the `GameManager` actor, which routes messages to the appropriate
 * game actor instance.
 *
 * The route prefix is determined by the lowercase name of the game type (e.g., `tictactoe`). JSON payloads for moves
 * are dynamically decoded using the game-specific `MovePayload` decoder from the registry.
 *
 * Route Summary:
 * - POST   /{gameType}/{gameId}/move    - Submit a move to the specified game
 * - GET    /{gameType}/{gameId}/status  - Fetch the current state of a game
 */
class GameRoutes(gameType: GameType, system: ActorSystem[Command]) {
  implicit val scheduler: Scheduler = system.scheduler
  implicit val timeout: Timeout = 3.seconds
  implicit val ec: ExecutionContext = system.executionContext
  implicit val classic: ClassicActorSystemProvider = system.classicSystem

  private val gameTypePrefix = gameType.toString.toLowerCase

  /**
   * Retrieves the game-specific module for the given `gameType` from the `GameRegistry`.
   *
   * This module provides typeclass instances (e.g., decoders) and logic specific to the game, and is required for
   * decoding move payloads and interacting with the game actor.
   *
   * Throws an `IllegalArgumentException` if no module is registered for the given type, which should only happen if the
   * `GameType` is unknown or the registry is misconfigured.
   */
  private val module = GameRegistry.forType(gameType).getOrElse(
    throw new IllegalArgumentException(s"No module registered for $gameType")
  ).module

  /**
   * Parses and decodes a JSON payload from an HTTP request using the provided Circe decoder.
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

    /**
     * @route POST /{gameType}/{id}/move
     * @auth Requires Bearer token
     * @pathParam gameType The type of the game (e.g., tictactoe)
     * @pathParam id The ID of the game to make a move in
     * @bodyParam MovePayload A game-specific JSON payload for the move (structure depends on gameType)
     * @response 200 Updated game state after the move is applied
     * @response 400 If the JSON payload is malformed or invalid for the game type
     * @response 404 If the game is not found or the move is invalid
     * @response 500 Unexpected response from GameManager
     *
     * Submits a move to the specified game using the authenticated player ID and a dynamic, game-specific move format.
     */
    path(Segment / "move") { gameId =>
      post {
        authenticatePlayer { player =>
          extractRequest { req =>
            onSuccess(decodeJsonEntity(module.moveDecoder)(req)) {
              case Right(move) =>
                val op = GameOperation.MakeMove(player.id, move)
                onSuccess(system.ask[GameResponse](GameManager.RunGameOperation(gameId, op, _))) {
                  case GameStatus(state)    => complete(state)
                  case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
                  case unknown => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
                }

              case Left(decodingError) =>
                complete(StatusCodes.BadRequest -> s"Invalid JSON: $decodingError")
            }
          }
        }
      }
    } ~
    /**
     * @route GET /{gameType}/{id}/status
     * @pathParam gameType The type of the game (e.g., tictactoe)
     * @pathParam id The ID of the game to check status
     * @response 200 Current game state
     * @response 404 If the game is not found
     * @response 500 Unexpected response from GameManager
     *
     * Fetches the current state of the specified game.
     */
    path(Segment / "status") { gameId =>
      get {
        onSuccess(system.ask[GameResponse](GameManager.RunGameOperation(gameId, GameOperation.GetState, _))) {
          case GameStatus(state)    => complete(state)
          case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
          case unknown              => complete(StatusCodes.InternalServerError -> s"Unexpected response: $unknown")
        }
      }
    }
  }
}
