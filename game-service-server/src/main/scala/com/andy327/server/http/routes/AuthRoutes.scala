package com.andy327.server.http.routes

import java.util.UUID

import scala.util.{Failure, Success, Try}

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import com.andy327.server.auth.UserContext
import com.andy327.server.config.JwtConfig
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.Player

/**
 * HTTP routes for authentication and token generation.
 *
 * This class defines an endpoint that allows clients to authenticate as a player by supplying a name
 * and an optional UUID. The server responds with a JWT token representing the authenticated player.
 *
 * Route Summary:
 * - POST /auth/token   - Register or authenticate a player and receive a signed JWT
 * - GET  /auth/whoami  - Return the player's ID and name extracted from the Authorization token
 */
class AuthRoutes {
  val routes: Route = pathPrefix("auth") {

    /**
     * @route POST /auth/token
     * @bodyParam PlayerRequest JSON object with a required `name` (String) and optional `id` (UUID string)
     * @response 200 JSON object containing a signed JWT token for the player: `{ "token": "<jwt>" }`
     * @response 400 If the provided UUID is malformed
     *
     * Registers or authenticates a player using the provided name and optional ID.
     * - If `id` is provided, it must be a valid UUID and is used as the player's ID.
     * - If `id` is omitted, a new UUID is generated for the player.
     *
     * A signed JWT is returned containing the player's ID and name, which can be used for authenticated requests to
     * other parts of the API.
     */
    path("token") {
      post {
        entity(as[PlayerRequest]) { req =>
          val maybePlayer: Either[String, Player] = req.id match {
            case Some(idStr) =>
              Try(UUID.fromString(idStr)) match {
                case Success(uuid) => Right(Player(uuid, req.name))
                case Failure(_)    => Left("Invalid UUID format in 'id' field")
              }
            case None =>
              Right(Player(req.name)) // generate random UUID
          }

          maybePlayer match {
            case Right(player) =>
              val user = UserContext(player.id.toString, player.name)
              val token = JwtCirce.encode(user.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
              complete(Map("token" -> token))

            case Left(errorMessage) =>
              complete(StatusCodes.BadRequest -> Map("error" -> errorMessage))
          }
        }
      }
    } ~
    /**
     * @route GET /auth/whoami
     * @auth Requires Bearer token in the Authorization header
     * @response 200 JSON object with `id` and `name` of the authenticated player
     * @response 401 If the Authorization header is missing, the token is invalid or expired,
     *               the payload cannot be decoded into a `UserContext`, or the player ID is malformed
     *
     * Returns the identity of the currently authenticated player.
     *
     * This endpoint extracts a Bearer token from the Authorization header, validates it using the server's JWT secret,
     * and decodes the token into a `UserContext`. It then builds a `Player` object from the `UserContext` and returns
     * the player's UUID and name.
     */
    path("whoami") {
      authenticatePlayer { player =>
        get {
          // complete(player)
          complete(Map("id" -> player.id.toString, "name" -> player.name))
        }
      }
    }
  }
}
