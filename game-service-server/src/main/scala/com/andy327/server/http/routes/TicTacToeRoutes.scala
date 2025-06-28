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
import com.andy327.server.actors.core.GameManager.{ErrorResponse, GameResponse, GameStatus}
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.TicTacToeMoveRequest

/**
 * HTTP routes for managing Tic-Tac-Toe games.
 *
 * This class defines endpoints for making moves and querying game status. Requests are forwarded to the GameManager
 * actor, which handles routing to individual game actors. Authentication is required for most operations (via JWT in
 * the Authorization header).
 *
 * Route Summary:
 * - POST   /tictactoe/{gameId}/move    - Submit a move to the specified game
 * - GET    /tictactoe/{gameId}/status  - Fetch the current state of a game
 */
class TicTacToeRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("tictactoe") {

    /**
     * @route POST /tictactoe/{id}/move
     * @auth Requires Bearer token
     * @pathParam id The ID of the game to make a move in
     * @bodyParam TicTacToeMoveRequest JSON { "row": 0, "col": 1 }
     * @response 200 Updated game state after the move is applied
     * @response 404 If the game is not found or move is invalid
     * @response 500 Unexpected response from GameManager
     *
     * Submits a move in the given game as the authenticated player.
     */
    path(Segment / "move") { gameId =>
      post {
        authenticatePlayer { player =>
          entity(as[TicTacToeMoveRequest]) { case TicTacToeMoveRequest(row, col) =>
            onSuccess(
              system.ask[GameResponse] { replyTo =>
                val move = MovePayload.TicTacToeMove(row, col)
                val op = GameOperation.MakeMove(player.id, move)
                GameManager.RunGameOperation(gameId, op, replyTo)
              }
            ) {
              case GameStatus(state)    => complete(state)
              case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
              case unknown              => complete(StatusCodes.InternalServerError, s"Unexpected response: $unknown")
            }
          }
        }
      }
    } ~
    /**
     * @route GET /tictactoe/{id}/status
     * @pathParam id The ID of the game to check status
     * @response 200 Current game state
     * @response 404 If the game is not found
     * @response 500 Unexpected response from GameManager
     *
     * Fetches the current state of the specified game.
     */
    path(Segment / "status") { gameId =>
      get {
        onSuccess(
          system.ask[GameResponse](replyTo => GameManager.RunGameOperation(gameId, GameOperation.GetState, replyTo))
        ) {
          case GameStatus(state)    => complete(state)
          case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
          case unknown              => complete(StatusCodes.InternalServerError, s"Unexpected response: $unknown")
        }
      }
    }
  }
}
