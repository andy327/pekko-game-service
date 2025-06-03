package com.andy327.server.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.model.tictactoe.Location
import com.andy327.server.actors.GameManager
import com.andy327.server.actors.GameManager.{ErrorResponse, ForwardGetStatus, ForwardMove, GameResponse, GameState}
import com.andy327.server.http.JsonProtocol._
import com.andy327.server.http.Move

/**
 * HTTP routes for managing Tic-Tac-Toe games.
 *
 * This class defines endpoints for creating games, making moves, and querying game status.
 * Requests are forwarded to the GameManager actor, which handles routing to individual game actors.
 */
class TicTacToeRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("game") {

    /**
     * @route POST /game
     * @queryParam playerX Player ID for player X
     * @queryParam playerO Player ID for player O
     * @response 200 gameId as plain text
     *
     * Create a new Tic-Tac-Toe game with the specified player IDs.
     */
    pathEndOrSingleSlash {
      post {
        parameters("playerX", "playerO") { (p1, p2) =>
          onSuccess(system.ask(GameManager.CreateTicTacToe(p1, p2, _))) { gameId =>
            complete(gameId)
          }
        }
      }
    } ~
    /**
     * @route POST /game/{id}/move
     * @pathParam id The ID of the game
     * @bodyParam Move JSON { "playerId": "X", "row": 0, "col": 1 }
     * @response 200 TicTacToeStatus
     * @response 404 if the game is not found or move is invalid
     *
     * Submit a move for a player in a given game.
     */
    path(Segment / "move") { gameId =>
      post {
        entity(as[Move]) { case Move(playerId, row, col) =>
          onSuccess(
            system.ask[GameResponse](senderRef => ForwardMove(gameId, playerId, Location(row, col), senderRef))
          ) {
            case GameState(status)    => complete(status)
            case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
          }
        }
      }
    } ~
    /**
     * @route GET /game/{id}/status
     * @pathParam id The ID of the game
     * @response 200 TicTacToeStatus
     * @response 404 if the game is not found
     *
     * Fetch the current state of the specified game.
     */
    path(Segment / "status") { gameId =>
      get {
        onSuccess(system.ask[GameResponse](senderRef => ForwardGetStatus(gameId, senderRef))) {
          case GameState(status)    => complete(status)
          case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
        }
      }
    }
  }
}
