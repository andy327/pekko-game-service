package com.andy327.server.http.auth

import java.util.UUID

import scala.util.{Failure, Success, Try}

import io.circe.parser.decode
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{AuthorizationFailedRejection, Directive1}
import pdi.jwt._

import com.andy327.server.auth.UserContext
import com.andy327.server.config.JwtConfig.secretKey
import com.andy327.server.lobby.Player

/**
 * Provides a custom Pekko HTTP directive that authenticates players using JWT tokens.
 *
 * The directive parses the Authorization header, validates the JWT using the configured secret, extracts the
 * UserContext payload, and converts it into a Player object.
 */
object JwtPlayerDirectives {

  /**
   * Custom directive that extracts a Player from a valid JWT Authorization header.
   *
   * This directive:
   *  1. Looks for a Bearer token in the Authorization header.
   *  2. Verifies the token using the secret key and HS256 algorithm.
   *  3. Decodes the payload into a `UserContext` using Circe.
   *  4. Converts the player ID (a String) into a UUID.
   *  5. Returns a `Player` instance to downstream routes.
   *
   * If any step fails, it rejects the request with AuthorizationFailedRejection.
   *
   * @return A Pekko directive that provides an authenticated Player or rejects the request.
   */
  def authenticatePlayer: Directive1[Player] =
    optionalHeaderValueByName("Authorization").flatMap {
      case Some(header) if header.startsWith("Bearer ") =>
        val token = header.stripPrefix("Bearer ").trim

        // Validate the JWT and extract the payload as a JSON object
        JwtCirce.decodeJson(token, secretKey, Seq(JwtAlgorithm.HS256)) match {
          case Success(json) =>
            // Convert the raw JSON into a strongly-typed UserContext
            decode[UserContext](json.noSpaces) match {
              case Right(userContext) =>
                // Parse the player id string into a UUID, then build a Player
                Try(UUID.fromString(userContext.id)) match {
                  case Success(uuid) => provide(Player(uuid, userContext.name))
                  case Failure(_)    => reject(AuthorizationFailedRejection)
                }
              case Left(_) => reject(AuthorizationFailedRejection)
            }

          case Failure(_) => reject(AuthorizationFailedRejection)
        }

      // If no Authorization header is present or it doesn't start with Bearer
      case _ => reject(AuthorizationFailedRejection)
    }
}
