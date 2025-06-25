package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout

import com.andy327.model.core.{GameType, PlayerId}
import com.andy327.model.tictactoe.{GameError, Location}
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{
  ErrorResponse,
  GameResponse,
  GameStarted,
  GameStatus,
  LobbiesListed,
  LobbyCreated,
  LobbyJoined
}
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.{GameState, TicTacToeMove}
import com.andy327.server.lobby.IncomingPlayer
import com.andy327.server.lobby.IncomingPlayerOps._

/**
 * HTTP routes for managing Tic-Tac-Toe games.
 *
 * This class defines endpoints for creating/joining/listing lobbies, making moves, and querying game status.
 * Requests are forwarded to the GameManager actor, which handles routing to individual game actors.
 */
class TicTacToeRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("tictactoe") {

    /**
     * @route POST /tictactoe/lobby
     * @bodyParam Player JSON { "name": "alice" } or { "id": "uuid", "name": "alice" }
     * @response 200 JSON { "gameId": "...", "player": { "id": "uuid", "name": "..." } }
     * @response 500 Unexpected response from GameManager
     *
     * Create a new game lobby. The player may provide an optional UUID. If omitted, a new UUID is assigned.
     */
    path("lobby") {
      post {
        entity(as[IncomingPlayer]) { incoming =>
          val player = incoming.toPlayer
          onSuccess(system.ask[GameResponse](replyTo => GameManager.CreateLobby(GameType.TicTacToe, player, replyTo))) {
            case created: LobbyCreated => complete(created)
            case other                 => complete(StatusCodes.InternalServerError -> s"Unexpected: $other")
          }
        }
      }
    } ~
    /**
     * @route POST /tictactoe/lobby/{gameId}/join
     * @pathParam gameId The ID of the lobby to join
     * @bodyParam Player JSON { "name": "bob" } or { "id": "uuid", "name": "bob" }
     * @response 200 LobbyJoined containing the joined game's metadata
     * @response 400 If the join request is invalid (e.g., lobby full, already joined, nonexistent)
     * @response 500 Unexpected response from GameManager
     *
     * Join an existing game lobby. The player may provide an optional UUID. If omitted, a new UUID is assigned.
     */
    path("lobby" / Segment / "join") { gameId =>
      post {
        entity(as[IncomingPlayer]) { incoming =>
          val player = incoming.toPlayer
          onSuccess(system.ask[GameResponse](replyTo => GameManager.JoinLobby(gameId, player, replyTo))) {
            case joined: LobbyJoined => complete(joined)
            case ErrorResponse(msg)  => complete(StatusCodes.BadRequest -> msg)
            case other               => complete(StatusCodes.InternalServerError -> s"Unexpected: $other")
          }
        }
      }
    } ~
    /**
     * @route POST /tictactoe/lobby/{gameId}/start
     * @pathParam gameId The ID of the lobby to start
     * @bodyParam PlayerId of the host
     * @response 200 The ID of the started game as plain text
     * @response 400 If the game cannot be started
     * @response 500 Unexpected response from GameManager
     *
     * Start a game from a lobby as the host.
     */
    path("lobby" / Segment / "start") { gameId =>
      post {
        entity(as[PlayerId]) { playerId =>
          onSuccess(system.ask[GameResponse](replyTo => GameManager.StartGame(gameId, playerId, replyTo))) {
            case GameStarted(id)        => complete(id)
            case ErrorResponse(message) => complete(StatusCodes.BadRequest -> message)
            case other                  => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
          }
        }
      }
    } ~
    /**
     * @route GET /tictactoe/lobbies
     * @response 200 List of GameMetadata objects representing all open lobbies
     * @response 500 Unexpected response from GameManager
     *
     * List all open lobbies.
     */
    path("lobbies") {
      get {
        onSuccess(system.ask[GameResponse](GameManager.ListLobbies)) {
          case LobbiesListed(lobbies) => complete(lobbies)
          case other                  => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
        }
      }
    } ~
    /**
     * @route POST /tictactoe/{id}/move
     * @pathParam id The ID of the game to make a move in
     * @bodyParam TicTacToeMove JSON { "playerId": "uuid", "row": 0, "col": 1 }
     * @response 200 Updated game state
     * @response 404 If the game is not found or move is invalid
     * @response 500 Unexpected response from GameManager
     *
     * Submit a move for a player in a given game.
     */
    path(Segment / "move") { gameId =>
      post {
        entity(as[TicTacToeMove]) { case TicTacToeMove(player, row, col) =>
          onSuccess(
            system.ask[GameResponse] { replyTo =>
              val dummyRef = system.ignoreRef[Either[GameError, GameState]]
              val gameCmd = TicTacToeActor.MakeMove(player, Location(row, col), dummyRef)
              GameManager.ForwardToGame(gameId, gameCmd, Some(replyTo))
            }
          ) {
            case GameStatus(state)    => complete(state)
            case ErrorResponse(error) => complete(StatusCodes.NotFound -> error)
            case unknown              => complete(StatusCodes.InternalServerError, s"Unexpected response: $unknown")
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
          case unknown              => complete(StatusCodes.InternalServerError, s"Unexpected response: $unknown")
        }
      }
    }
  }
}
