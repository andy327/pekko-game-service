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

/** HTTP routes for authentication and token generation.
  *
  * This class defines an endpoint that allows clients to authenticate as a player by supplying a name and an optional
  * UUID. The server responds with a JWT token representing the authenticated player.
  *
  * Route Summary:
  *   - POST /auth/token - Register or authenticate a player and receive a signed JWT
  *   - GET /auth/whoami - Return the player's ID and name extracted from the Authorization token
  */
class AuthRoutes {
  val routes: Route = pathPrefix("auth") {

    /** Registers or authenticates a player and returns a signed JWT for use in subsequent requests.
      *
      * If `id` is provided it must be a valid UUID and is used as the player's identity; if omitted, a new UUID is
      * generated. The returned token encodes the player's ID and name and is accepted by all authenticated endpoints.
      *
      * - Body: `PlayerRequest` — `name` (String, required), `id` (UUID string, optional)
      * - 200: `{ "token": "<jwt>" }` — signed JWT for the player
      * - 400: malformed UUID in the `id` field
      */
    path("token") {
      post {
        entity(as[PlayerRequest]) { req =>
          // TODO(#29): identity is self-asserted — any caller can mint a token for any UUID. Replace the
          // client-supplied `id` with real credentialing (account store + credential check at issuance).
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
              // TODO(#29): no iat/exp claims — tokens never expire. Add a JwtClaim with expiration when real
              // authentication lands; JwtPlayerDirectives already rejects expired tokens once the claim exists.
              val token = JwtCirce.encode(user.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
              complete(Map("token" -> token))

            case Left(errorMessage) =>
              complete(StatusCodes.BadRequest -> Map("error" -> errorMessage))
          }
        }
      }
    } ~
    /** Returns the identity of the currently authenticated player.
      *
      * Validates the Bearer token, decodes it into a `UserContext`, and returns the player's UUID and name.
      *
      * - Auth: Bearer token required
      * - 200: `Player` with the authenticated player's `id` (UUID) and `name` (String)
      * - 401: missing token, invalid or expired token, undecodable payload, or malformed player ID
      */
    path("whoami") {
      authenticatePlayer { player =>
        get {
          complete(player)
        }
      }
    }
  }
}
