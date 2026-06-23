package com.andy327.server.http.routes

import cats.effect.unsafe.IORuntime

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

import com.andy327.persistence.db.{Account, InMemoryPlayerHistoryRepository, PlayerHistoryRepository}
import com.andy327.server.auth.{IdentityProvider, JwtIssuer, RegisterError, UserContext}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.auth.{
  AuthValidation,
  ChangePasswordRequest,
  LoginRequest,
  PlayerGameSummary,
  PlayerHistory,
  RegisterRequest
}
import com.andy327.server.http.json.JsonProtocol._

/** HTTP routes for registration, authentication, and token issuance.
  *
  * Identity is established by verifying credentials against the [[com.andy327.server.auth.IdentityProvider]], not
  * asserted by the client: the
  * token a caller receives attests to the account the server authenticated, and its id comes from that account.
  *
  * Route Summary:
  *   - POST /auth/register - Create an account and receive a signed JWT
  *   - POST /auth/token - Authenticate with credentials and receive a signed JWT
  *   - POST /auth/password - Change the authenticated account's password
  *   - GET /auth/whoami - Return the player's ID and name extracted from the Authorization token
  *   - GET /auth/me/history - Return the authenticated player's completed-game history
  */
class AuthRoutes(
    identityProvider: IdentityProvider,
    playerHistoryRepo: PlayerHistoryRepository = new InMemoryPlayerHistoryRepository
)(implicit runtime: IORuntime) {
  private val jwtIssuer = JwtIssuer.fromConfig()

  private def tokenFor(account: Account): String =
    jwtIssuer.issue(UserContext(account.id.toString, account.username))

  val routes: Route = pathPrefix("auth") {

    /** Registers a new account and returns a signed JWT for it.
      *
      * - Body: `RegisterRequest` — `username`, `email`, `password`
      * - 201: `{ "token": "<jwt>" }` — signed JWT for the new account
      * - 400: a field is blank, malformed, or out of range
      * - 409: the email is already registered
      */
    path("register") {
      post {
        entity(as[RegisterRequest]) { req =>
          AuthValidation.validateRegister(req) match {
            case Left(error) =>
              complete(StatusCodes.BadRequest -> Map("error" -> error))
            case Right(valid) =>
              onSuccess(identityProvider.register(valid.username, valid.email, valid.password).unsafeToFuture()) {
                case Right(account) =>
                  complete(StatusCodes.Created -> Map("token" -> tokenFor(account)))
                case Left(RegisterError.EmailAlreadyRegistered) =>
                  complete(StatusCodes.Conflict -> Map("error" -> "Email already registered"))
              }
          }
        }
      }
    } ~
    /** Authenticates an existing account and returns a signed JWT.
      *
      * - Body: `LoginRequest` — `email`, `password`
      * - 200: `{ "token": "<jwt>" }` — signed JWT for the authenticated account
      * - 400: email or password is blank
      * - 401: unknown email or wrong password (not distinguished)
      */
    path("token") {
      post {
        entity(as[LoginRequest]) { req =>
          AuthValidation.validateLogin(req) match {
            case Left(error) =>
              complete(StatusCodes.BadRequest -> Map("error" -> error))
            case Right(valid) =>
              onSuccess(identityProvider.authenticate(valid.email, valid.password).unsafeToFuture()) {
                case Right(account) => complete(Map("token" -> tokenFor(account)))
                case Left(_)        => complete(StatusCodes.Unauthorized -> Map("error" -> "Invalid email or password"))
              }
          }
        }
      }
    } ~
    /** Changes the authenticated account's password.
      *
      * - Auth: Bearer token required (identifies the account)
      * - Body: `ChangePasswordRequest` — `currentPassword`, `newPassword`
      * - 204: password changed
      * - 400: the current password is blank or the new password is out of range
      * - 401: missing, invalid, or expired token
      * - 403: the current password is incorrect
      *
      * Existing tokens are not revoked by a change — they remain valid until they expire.
      */
    path("password") {
      post {
        authenticatePlayer { player =>
          entity(as[ChangePasswordRequest]) { req =>
            AuthValidation.validatePasswordChange(req) match {
              case Left(error) =>
                complete(StatusCodes.BadRequest -> Map("error" -> error))
              case Right(_) =>
                onSuccess(
                  identityProvider.changePassword(player.id, req.currentPassword, req.newPassword).unsafeToFuture()
                ) {
                  case Right(_) => complete(StatusCodes.NoContent)
                  case Left(_)  => complete(StatusCodes.Forbidden -> Map("error" -> "Current password is incorrect"))
                }
            }
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
    } ~
    /** Returns the authenticated player's completed-game history, most recently finished first.
      *
      * The history is read for the account the token identifies, so a caller only ever sees their own games.
      *
      * - Auth: Bearer token required (identifies the player)
      * - 200: `PlayerHistory` — the player's completed games (empty `games` if none)
      * - 401: missing, invalid, or expired token
      */
    path("me" / "history") {
      get {
        authenticatePlayer { player =>
          onSuccess(playerHistoryRepo.findByPlayer(player.id).unsafeToFuture()) { records =>
            val games = records.map(r => PlayerGameSummary(r.gameId, r.gameType, r.result, r.forfeit, r.finishedAt))
            complete(PlayerHistory(games))
          }
        }
      }
    }
  }
}
