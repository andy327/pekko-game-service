package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.model.core.GameType
import com.andy327.model.tictactoe.{GameError, Location}
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{ErrorResponse, GameResponse, GameStatus}
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.{GameState, TicTacToeMove}

/**
 * HTTP routes for managing Tic-Tac-Toe games.
 *
 * This class defines endpoints for creating games, making moves, and querying game status.
 * Requests are forwarded to the GameManager actor, which handles routing to individual game actors.
 */
class TicTacToeRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("tictactoe") {

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
          onSuccess(system.ask(GameManager.CreateGame(GameType.TicTacToe, Seq(p1, p2), _))) { gameId =>
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
        entity(as[TicTacToeMove]) { case TicTacToeMove(playerId, row, col) =>
          onSuccess(
            system.ask[GameResponse] { replyTo =>
              val dummyRef = system.ignoreRef[Either[GameError, GameState]]
              val gameCmd = TicTacToeActor.MakeMove(playerId, Location(row, col), dummyRef)
              GameManager.ForwardToGame(gameId, gameCmd, Some(replyTo))
            }
          ) {
            case GameStatus(state)    => complete(state)
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
        onSuccess(
          system.ask[GameResponse] { replyTo =>
            val dummyRef = system.ignoreRef[Either[GameError, GameState]]
            val gameCmd = TicTacToeActor.GetState(dummyRef)
            GameManager.ForwardToGame(gameId, gameCmd, Some(replyTo))
          }
        ) {
          case GameStatus(state)    => complete(state)
          case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
        }
      }
    }
  }
}
