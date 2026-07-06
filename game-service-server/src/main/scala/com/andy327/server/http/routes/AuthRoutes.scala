package com.andy327.server.http.routes

import java.time.Instant

import cats.effect.unsafe.IORuntime

import org.apache.pekko.http.scaladsl.model.headers.`Retry-After`
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}

import com.andy327.actor.lobby.Player
import com.andy327.persistence.db.Account
import com.andy327.server.auth.{
  AuthRateLimiter,
  IdentityProvider,
  JwtIssuer,
  NoOpAuthRateLimiter,
  NoOpRevocationStore,
  RateLimitOutcome,
  RegisterError,
  RevocationStore,
  UserContext
}
import com.andy327.server.config.{AuthRateLimitConfig, JwtConfig}
import com.andy327.server.http.auth.{
  AuthValidation,
  ChangePasswordRequest,
  JwtAuthenticator,
  LoginRequest,
  RegisterRequest
}
import com.andy327.server.http.json.JsonProtocol._

/** HTTP routes for registration, authentication, and token issuance.
  *
  * Identity is established by verifying credentials against the [[com.andy327.server.auth.IdentityProvider]], not
  * asserted by the client: the
  * token a caller receives attests to the account the server authenticated, and its id comes from that account.
  *
  * The credential-bearing endpoints are rate limited via an [[server.auth.AuthRateLimiter]]: a per-IP fixed-window
  * throttle guards register/login/password against spraying, and repeated failed logins lock an account (by email) for
  * a cool-off period. Both default to no-ops.
  *
  * Route Summary:
  *   - POST /auth/register - Create an account and receive a signed JWT
  *   - POST /auth/token - Authenticate with credentials and receive a signed JWT
  *   - POST /auth/password - Change the authenticated account's password
  *   - POST /auth/logout - Revoke the account's outstanding tokens (log out everywhere)
  *   - GET /auth/whoami - Return the player's ID and name extracted from the Authorization token
  */
class AuthRoutes(
    identityProvider: IdentityProvider,
    rateLimiter: AuthRateLimiter = NoOpAuthRateLimiter,
    limits: AuthRateLimitConfig = AuthRateLimitConfig.fromConfig(),
    authenticator: JwtAuthenticator = new JwtAuthenticator(),
    revocationStore: RevocationStore = NoOpRevocationStore
)(implicit runtime: IORuntime) {
  private val jwtIssuer = JwtIssuer.fromConfig()

  private def tokenFor(account: Account): String =
    jwtIssuer.issue(UserContext(account.id.toString, account.username))

  /** The originating client IP, preferring the first `X-Forwarded-For` entry (the real client when behind a proxy such
    * as Render), then the direct remote address, and finally the literal `"unknown"` when neither is available.
    */
  private def clientIp: Directive1[String] =
    optionalHeaderValueByName("X-Forwarded-For").flatMap {
      case Some(xff) if xff.trim.nonEmpty => provide(xff.split(",").head.trim)
      case _                              => extractClientIP.map(_.toOption.map(_.getHostAddress).getOrElse("unknown"))
    }

  /** Counts one request against the per-IP throttle for `endpoint` and either runs `inner` or completes with 429. */
  private def ipThrottle(endpoint: String, ip: String)(inner: => Route): Route =
    onSuccess(rateLimiter.throttle(s"$endpoint:ip:$ip", limits.ipMaxAttempts, limits.ipWindow).unsafeToFuture()) {
      case RateLimitOutcome.Allowed             => inner
      case RateLimitOutcome.Limited(retryAfter) => tooManyRequests("Too many requests; slow down", retryAfter)
    }

  /** A 429 response carrying a `Retry-After` header (at least one second) and a JSON error body. */
  private def tooManyRequests(message: String, retryAfter: scala.concurrent.duration.FiniteDuration): Route =
    respondWithHeader(`Retry-After`(math.max(1L, retryAfter.toSeconds))) {
      complete(StatusCodes.TooManyRequests -> Map("error" -> message))
    }

  /** Lockout key for an account, normalized to lower case so it matches regardless of how the email was cased. */
  private def lockoutKey(email: String): String = s"login:email:${email.toLowerCase}"

  /** Revokes every token the account currently holds (issued before now), then completes with `status`. The cutoff is
    * kept for the token lifetime, after which those tokens have expired anyway. Used by logout and password change.
    */
  private def revokeTokensThen(player: Player, status: StatusCode): Route = {
    val revoked = revocationStore.revokeBefore(player.id, Instant.now(), JwtConfig.ttl).as(status)
    onSuccess(revoked.unsafeToFuture())(complete(_))
  }

  val routes: Route = pathPrefix("auth") {

    /** Registers a new account and returns a signed JWT for it.
      *
      * - Body: `RegisterRequest` — `username`, `email`, `password`
      * - 201: `{ "token": "<jwt>" }` — signed JWT for the new account
      * - 400: a field is blank, malformed, or out of range
      * - 409: the email is already registered
      * - 429: too many requests from this IP; retry after the `Retry-After` header
      */
    path("register") {
      post {
        clientIp { ip =>
          ipThrottle("register", ip) {
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
        }
      }
    } ~
    /** Authenticates an existing account and returns a signed JWT.
      *
      * - Body: `LoginRequest` — `email`, `password`
      * - 200: `{ "token": "<jwt>" }` — signed JWT for the authenticated account
      * - 400: email or password is blank
      * - 401: unknown email or wrong password (not distinguished)
      * - 429: too many requests from this IP, or the account is temporarily locked after repeated failures
      */
    path("token") {
      post {
        clientIp { ip =>
          ipThrottle("login", ip) {
            entity(as[LoginRequest]) { req =>
              AuthValidation.validateLogin(req) match {
                case Left(error) =>
                  complete(StatusCodes.BadRequest -> Map("error" -> error))
                case Right(valid) =>
                  val lockKey = lockoutKey(valid.email)
                  onSuccess(rateLimiter.lockStatus(lockKey).unsafeToFuture()) {
                    case Some(retryAfter) =>
                      tooManyRequests("Account temporarily locked after repeated failed logins", retryAfter)
                    case None =>
                      // Clear the failure count on success, or record a failure (which may trip the lock) on rejection.
                      val attempt = identityProvider.authenticate(valid.email, valid.password).flatMap {
                        case success @ Right(_) => rateLimiter.clearFailures(lockKey).as(success)
                        case failure @ Left(_)  =>
                          rateLimiter
                            .recordFailure(
                              lockKey,
                              limits.lockoutThreshold,
                              limits.lockoutWindow,
                              limits.lockoutDuration
                            )
                            .as(failure)
                      }
                      onSuccess(attempt.unsafeToFuture()) {
                        case Right(account) => complete(Map("token" -> tokenFor(account)))
                        case Left(_)        =>
                          complete(StatusCodes.Unauthorized -> Map("error" -> "Invalid email or password"))
                      }
                  }
              }
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
      * - 429: too many requests from this IP; retry after the `Retry-After` header
      *
      * A successful change revokes the account's existing tokens, so tokens issued before it stop being accepted.
      */
    path("password") {
      post {
        clientIp { ip =>
          ipThrottle("password", ip) {
            authenticator.authenticatePlayer { player =>
              entity(as[ChangePasswordRequest]) { req =>
                AuthValidation.validatePasswordChange(req) match {
                  case Left(error) =>
                    complete(StatusCodes.BadRequest -> Map("error" -> error))
                  case Right(_) =>
                    onSuccess(
                      identityProvider.changePassword(player.id, req.currentPassword, req.newPassword).unsafeToFuture()
                    ) {
                      case Right(_) => revokeTokensThen(player, StatusCodes.NoContent)
                      case Left(_) => complete(StatusCodes.Forbidden -> Map("error" -> "Current password is incorrect"))
                    }
                }
              }
            }
          }
        }
      }
    } ~
    /** Logs the caller out by revoking every token their account currently holds ("log out everywhere").
      *
      * - Auth: Bearer token required
      * - 204: the account's outstanding tokens are revoked; they will no longer be accepted
      * - 401: missing, invalid, or expired token
      */
    path("logout") {
      post {
        authenticator.authenticatePlayer { player =>
          revokeTokensThen(player, StatusCodes.NoContent)
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
      authenticator.authenticatePlayer { player =>
        get {
          complete(player)
        }
      }
    }
  }
}
