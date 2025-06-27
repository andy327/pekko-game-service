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
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.core.GameManager.{
  ErrorResponse,
  GameResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyJoined
}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._

// TODO: depend on a new LobbyManager actor instead of GameManager
class LobbyRoutes(system: ActorSystem[GameManager.Command]) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("lobby") {

    /**
     * @route POST /lobby/create/{gameType}
     * @pathParam gameType The type of game to create a lobby for (e.g., "tictactoe")
     * @auth Requires Bearer token
     * @response 200 LobbyCreated { "gameId": "...", "player": { "id": "uuid", "name": "..." } }
     * @response 400 If the provided game type is invalid
     * @response 500 If an unexpected error occurs while creating the lobby
     *
     * Creates a new game lobby for the specified game type using the authenticated player.
     */
    path("create" / Segment) { gameTypeStr =>
      post {
        authenticatePlayer { player =>
          GameType.fromString(gameTypeStr) match {
            case Some(gameType) =>
              onSuccess(system.ask[GameResponse](replyTo => GameManager.CreateLobby(gameType, player, replyTo))) {
                case created: LobbyCreated => complete(created)
                case other                 => complete(StatusCodes.InternalServerError -> s"Unexpected: $other")
              }

            case None =>
              complete(StatusCodes.BadRequest -> s"Invalid game type: $gameTypeStr")
          }
        }
      }
    } ~
    /**
     * @route POST /lobby/{gameId}/join
     * @auth Requires Bearer token
     * @pathParam gameId The ID of the lobby to join
     * @response 200 LobbyJoined containing the joined lobby's metadata
     * @response 400 If the join request is invalid (e.g., lobby full, already joined, nonexistent)
     * @response 500 Unexpected response from GameManager
     *
     * Joins an existing lobby using the authenticated player.
     */
    path(Segment / "join") { gameId =>
      post {
        authenticatePlayer { player =>
          onSuccess(system.ask[GameResponse](replyTo => GameManager.JoinLobby(gameId, player, replyTo))) {
            case joined: LobbyJoined => complete(joined)
            case ErrorResponse(msg)  => complete(StatusCodes.BadRequest -> msg)
            case other               => complete(StatusCodes.InternalServerError -> s"Unexpected: $other")
          }
        }
      }
    } ~
    /**
     * @route POST /lobby/{gameId}/start
     * @auth Requires Bearer token
     * @pathParam gameId The ID of the lobby to start
     * @bodyParam PlayerId of the host
     * @response 200 The ID of the started game as plain text
     * @response 400 If the game cannot be started
     * @response 500 Unexpected response from GameManager
     *
     * Starts the game from the given lobby. Must be called by the host.
     */
    path(Segment / "start") { gameId =>
      post {
        authenticatePlayer { player =>
          onSuccess(system.ask[GameResponse](replyTo => GameManager.StartGame(gameId, player.id, replyTo))) {
            case GameStarted(id)        => complete(id)
            case ErrorResponse(message) => complete(StatusCodes.BadRequest -> message)
            case other                  => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
          }
        }
      }
    } ~
    /**
     * @route GET /lobby/list
     * @response 200 List of LobbyMetadata objects representing all open lobbies
     * @response 500 Unexpected response from GameManager
     *
     * Lists all open lobbies.
     */
    path("list") {
      get {
        onSuccess(system.ask[GameResponse](GameManager.ListLobbies)) {
          case LobbiesListed(lobbies) => complete(lobbies)
          case other                  => complete(StatusCodes.InternalServerError -> s"Unexpected response: $other")
        }
      }
    }
  }
}
