package com.andy327.server.http.auth

import java.util.UUID

import scala.util.{Failure, Success, Try}

import cats.effect.unsafe.IORuntime

import io.circe.parser.decode
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directive1
import org.apache.pekko.http.scaladsl.server.Directives._
import pdi.jwt._

import com.andy327.actor.lobby.Player
import com.andy327.server.auth.{NoOpRevocationStore, RevocationStore, UserContext}
import com.andy327.server.config.JwtConfig
import com.andy327.server.http.json.JsonProtocol._

/** Authenticates players from JWT bearer tokens, as an injectable component.
  *
  * The directive parses the Authorization header, validates the JWT using the configured secret, extracts the
  * `UserContext` payload, converts it into a `Player`, and rejects the token if the account has revoked everything
  * issued up to a later cutoff (see [[server.auth.RevocationStore]]).
  *
  * @param secretKey shared secret the tokens are signed with; defaults to the configured `jwt.secret`
  * @param revocationStore source of per-account revocation cutoffs; defaults to a no-op (nothing is ever revoked)
  */
class JwtAuthenticator(
    secretKey: String = JwtConfig.secretKey,
    revocationStore: RevocationStore = NoOpRevocationStore
)(implicit runtime: IORuntime = IORuntime.global) {

  /** Custom directive that extracts a Player from a valid JWT supplied in the Authorization header.
    *
    * This directive:
    *   1. Looks for a Bearer token in the Authorization header.
    *   2. Verifies the token using the secret key and HS256 algorithm.
    *   3. Decodes the payload into a `UserContext` using Circe.
    *   4. Converts the player ID (a String) into a UUID.
    *   5. Returns a `Player` instance to downstream routes.
    *
    * If any step fails, it rejects the request with an Unauthorized response.
    *
    * @return A Pekko directive that provides an authenticated Player or rejects the request.
    */
  def authenticatePlayer: Directive1[Player] =
    bearerToken(allowQueryParam = false).flatMap(validateToken)

  /** Variant of [[authenticatePlayer]] that also accepts the token as an `access_token` query parameter.
    *
    * The browser WebSocket API cannot set request headers on the handshake, so the WebSocket route uses this variant to
    * read the same JWT from the query string when no Authorization header is present. The header path is still
    * preferred and is the only one used by the REST API. In production the connection should be `wss`, so the token is
    * encrypted on the wire; combined with same-origin serving and short token lifetimes this keeps the query-string
    * token safe.
    */
  def authenticatePlayerAllowingQueryParam: Directive1[Player] =
    bearerToken(allowQueryParam = true).flatMap(validateToken)

  /** Extracts the raw JWT from the Authorization header, optionally falling back to the `access_token` query parameter.
    * Rejects with an Unauthorized response when no token can be found.
    */
  private def bearerToken(allowQueryParam: Boolean): Directive1[String] =
    optionalHeaderValueByName("Authorization").flatMap {
      case Some(header) if header.startsWith("Bearer ") =>
        provide(header.stripPrefix("Bearer ").trim)
      case _ if allowQueryParam =>
        parameter("access_token".?).flatMap {
          case Some(token) => provide(token)
          case None        => complete(StatusCodes.Unauthorized -> Map("error" -> "Missing access token"))
        }
      case _ =>
        complete(StatusCodes.Unauthorized -> Map("error" -> "Missing Authorization header"))
    }

  /** Validates a raw JWT and provides the [[Player]] it identifies, or rejects with an Unauthorized response. */
  private def validateToken(token: String): Directive1[Player] =
    // Validate the JWT (signature + expiry) and split registered claims (iat/exp) from the UserContext content
    JwtCirce.decode(token, secretKey, Seq(JwtAlgorithm.HS256)) match {
      case Success(claim) =>
        // Convert the content JSON into a strongly-typed UserContext
        decode[UserContext](claim.content) match {
          case Right(userContext) =>
            // Parse the player id string into a UUID, then build a Player
            playerFromContext(userContext) match {
              case Some(player) => rejectIfRevoked(player, claim.issuedAt)
              case None         => complete(StatusCodes.Unauthorized -> Map("error" -> "Invalid player ID or name"))
            }
          case Left(_) => complete(StatusCodes.Unauthorized -> Map("error" -> "Token payload could not be parsed"))
        }

      case Failure(_) => complete(StatusCodes.Unauthorized -> Map("error" -> "Token is invalid or expired"))
    }

  /** Provides the player unless their account has a revocation cutoff at or after the token's issued-at, in which case
    * the token predates a logout/password-change and is rejected. A token with no `iat` is treated as revoked whenever
    * a cutoff exists, since its freshness can't be proven.
    */
  private def rejectIfRevoked(player: Player, issuedAt: Option[Long]): Directive1[Player] =
    onSuccess(revocationStore.revokedBefore(player.id).unsafeToFuture()).flatMap {
      case Some(cutoff) if issuedAt.forall(_ * 1000 < cutoff.toEpochMilli) =>
        complete(StatusCodes.Unauthorized -> Map("error" -> "Token has been revoked"))
      case _ => provide(player)
    }

  /** Builds a [[Player]] from a decoded JWT payload, parsing the subject id as a UUID; `None` if it is malformed. */
  private def playerFromContext(user: UserContext): Option[Player] =
    Try(UUID.fromString(user.id)).toOption.map(uuid => Player(uuid, user.name))
}
