package com.andy327.server.http.routes

import java.time.Instant

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.http.scaladsl.model.headers.`Retry-After`
import org.apache.pekko.http.scaladsl.model.{StatusCode, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{Directive1, Route}

import com.andy327.actor.lobby.Player
import com.andy327.persistence.db.{Account, InMemoryUserRepository, UserRepository}
import com.andy327.server.auth.{
  AuthRateLimiter,
  EmailSender,
  IdentityProvider,
  InMemorySingleUseTokenStore,
  JwtIssuer,
  NoOpAuthRateLimiter,
  NoOpEmailSender,
  NoOpRevocationStore,
  RateLimitOutcome,
  RegisterError,
  RevocationStore,
  SingleUseTokenStore,
  TokenPurpose,
  UserContext
}
import com.andy327.server.config.{AuthRateLimitConfig, AuthResetConfig, AuthVerificationConfig, JwtConfig}
import com.andy327.server.http.auth.{
  AuthValidation,
  ChangePasswordRequest,
  ForgotPasswordRequest,
  JwtAuthenticator,
  LoginRequest,
  RegisterRequest,
  ResendVerificationRequest,
  ResetPasswordRequest,
  VerifyEmailRequest,
  WhoamiResponse
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
  *   - POST /auth/forgot-password - Email a single-use reset link for an account (always 202, never reveals existence)
  *   - POST /auth/reset-password - Set a new password using a reset token, revoking the account's older tokens
  *   - POST /auth/verify - Verify an email address using a token from the verification email
  *   - POST /auth/verify/resend - Email a fresh verification link for an account (always 202, never reveals existence)
  *   - POST /auth/logout - Revoke the account's outstanding tokens (log out everywhere)
  *   - GET /auth/whoami - Return the player's ID and name (and email-verification flag) from the Authorization token
  *
  * @param userRepo account lookups for the reset/verify flows and the whoami flag; must be the same store backing
  *   `identityProvider`
  * @param emailSender delivers the reset and verification emails; defaults to a no-op so nothing is sent unless a
  *   provider is wired
  * @param tokenStore mints and consumes single-use reset and verification tokens (keyed by purpose)
  * @param resetConfig reset-token policy (TTL)
  * @param verificationConfig verification-token policy (TTL)
  */
class AuthRoutes(
    identityProvider: IdentityProvider,
    rateLimiter: AuthRateLimiter = NoOpAuthRateLimiter,
    limits: AuthRateLimitConfig = AuthRateLimitConfig.fromConfig(),
    authenticator: JwtAuthenticator = new JwtAuthenticator(),
    revocationStore: RevocationStore = NoOpRevocationStore,
    userRepo: UserRepository = new InMemoryUserRepository,
    emailSender: EmailSender = NoOpEmailSender,
    tokenStore: SingleUseTokenStore = new InMemorySingleUseTokenStore,
    resetConfig: AuthResetConfig = AuthResetConfig.fromConfig(),
    verificationConfig: AuthVerificationConfig = AuthVerificationConfig.fromConfig()
)(implicit runtime: IORuntime) {
  private val logger = LoggerFactory.getLogger(getClass)
  private val jwtIssuer = JwtIssuer.fromConfig()

  /** The deliberately non-committal 202 body, identical for registered and unregistered addresses. */
  private val ForgotPasswordMessage = "If that email is registered, a reset link has been sent"

  /** The deliberately non-committal 202 body for resend, identical whether or not the address needs verification. */
  private val ResendVerificationMessage = "If that email needs verification, a new link has been sent"

  private def tokenFor(account: Account): String =
    jwtIssuer.issue(UserContext(account.id.toString, account.username))

  /** Issues a verification token for `account` and emails it, swallowing any failure so a send outage never fails the
    * enclosing request. Used on registration and on resend.
    */
  private def sendVerification(account: Account): IO[Unit] =
    tokenStore
      .issue(TokenPurpose.EmailVerification, account.id, verificationConfig.tokenTtl)
      .flatMap(emailSender.sendEmailVerification(account.email, _))
      .handleErrorWith(t => IO(logger.warn("verification email dispatch failed", t)))

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
      *
      * On success a verification email is dispatched; failure to send it never fails the registration.
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
                  val registered = identityProvider.register(valid.username, valid.email, valid.password).flatMap {
                    case created @ Right(account) => sendVerification(account).as(created)
                    case rejected @ Left(_)       => IO.pure(rejected)
                  }
                  onSuccess(registered.unsafeToFuture()) {
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
    /** Starts a password reset by emailing a single-use reset link to the account, if the address is registered.
      *
      * - Body: `ForgotPasswordRequest` — `email`
      * - 202: always, whether or not the email is registered, so the response never reveals account existence
      * - 429: too many requests from this IP; retry after the `Retry-After` header
      *
      * A registered address is mailed a fresh reset token; an unregistered (or blank) one is silently ignored. Any
      * failure to mint or send is swallowed so the outcome stays a uniform 202.
      */
    path("forgot-password") {
      post {
        clientIp { ip =>
          ipThrottle("forgot-password", ip) {
            entity(as[ForgotPasswordRequest]) { req =>
              val email = AuthValidation.normalizeForgotPassword(req).email
              val dispatch: IO[Unit] =
                if (email.isEmpty) IO.unit
                else
                  userRepo.findByEmail(email).flatMap {
                    case Some(account) =>
                      tokenStore
                        .issue(TokenPurpose.PasswordReset, account.id, resetConfig.tokenTtl)
                        .flatMap(emailSender.sendPasswordReset(account.email, _))
                    case None => IO.unit
                  }
              // Swallow lookup/send failures: a 500 only on registered addresses would itself leak existence.
              val settled = dispatch
                .handleErrorWith(t => IO(logger.warn("forgot-password dispatch failed", t)))
                .as(StatusCodes.Accepted)
              onSuccess(settled.unsafeToFuture())(status => complete(status -> Map("status" -> ForgotPasswordMessage)))
            }
          }
        }
      }
    } ~
    /** Completes a password reset using a token from the reset email.
      *
      * - Body: `ResetPasswordRequest` — `token`, `newPassword`
      * - 204: password set; the token is consumed and the account's tokens issued before now are revoked
      * - 400: the token is blank/missing/expired/already used, or the new password is out of range
      * - 429: too many requests from this IP; retry after the `Retry-After` header
      */
    path("reset-password") {
      post {
        clientIp { ip =>
          ipThrottle("reset-password", ip) {
            entity(as[ResetPasswordRequest]) { req =>
              AuthValidation.validateResetPassword(req) match {
                case Left(error) =>
                  complete(StatusCodes.BadRequest -> Map("error" -> error))
                case Right(_) =>
                  val reset: IO[Boolean] = tokenStore.consume(TokenPurpose.PasswordReset, req.token).flatMap {
                    case Some(accountId) =>
                      // Set the new hash, then advance the revocation cutoff so pre-reset tokens stop being accepted.
                      identityProvider.resetPassword(accountId, req.newPassword) *>
                        revocationStore.revokeBefore(accountId, Instant.now(), JwtConfig.ttl).as(true)
                    case None => IO.pure(false)
                  }
                  onSuccess(reset.unsafeToFuture()) {
                    case true  => complete(StatusCodes.NoContent)
                    case false => complete(StatusCodes.BadRequest -> Map("error" -> "Invalid or expired reset token"))
                  }
              }
            }
          }
        }
      }
    } ~
    /** Verifies an email address using a token from the verification email.
      *
      * - Body: `VerifyEmailRequest` — `token`
      * - 204: the address is verified; also returned when the account was already verified (idempotent)
      * - 400: the token is blank, unknown, expired, or already used
      * - 429: too many requests from this IP; retry after the `Retry-After` header
      */
    path("verify") {
      post {
        clientIp { ip =>
          ipThrottle("verify", ip) {
            entity(as[VerifyEmailRequest]) { req =>
              AuthValidation.validateVerifyEmail(req) match {
                case Left(error) =>
                  complete(StatusCodes.BadRequest -> Map("error" -> error))
                case Right(_) =>
                  val verified: IO[Boolean] = tokenStore.consume(TokenPurpose.EmailVerification, req.token).flatMap {
                    // markVerified is idempotent, so a fresh token for an already-verified account still 204s.
                    case Some(accountId) => userRepo.markVerified(accountId).as(true)
                    case None            => IO.pure(false)
                  }
                  onSuccess(verified.unsafeToFuture()) {
                    case true  => complete(StatusCodes.NoContent)
                    case false =>
                      complete(StatusCodes.BadRequest -> Map("error" -> "Invalid or expired verification token"))
                  }
              }
            }
          }
        }
      }
    } ~
    /** Resends a verification email for an address, if it belongs to an as-yet-unverified account.
      *
      * - Body: `ResendVerificationRequest` — `email`
      * - 202: always, whether or not the email is registered or already verified, so existence is never revealed
      * - 429: too many requests from this IP; retry after the `Retry-After` header
      *
      * A registered, unverified address is mailed a fresh token; anything else (unknown, blank, already verified) is
      * silently ignored, and any dispatch failure is swallowed so the outcome stays a uniform 202.
      */
    path("verify" / "resend") {
      post {
        clientIp { ip =>
          ipThrottle("verify-resend", ip) {
            entity(as[ResendVerificationRequest]) { req =>
              val email = AuthValidation.normalizeResendVerification(req).email
              val dispatch: IO[Unit] =
                if (email.isEmpty) IO.unit
                else
                  userRepo.findByEmail(email).flatMap {
                    case Some(account) if account.emailVerifiedAt.isEmpty => sendVerification(account)
                    case _                                                => IO.unit
                  }
              val settled = dispatch
                .handleErrorWith(t => IO(logger.warn("verify-resend dispatch failed", t)))
                .as(StatusCodes.Accepted)
              onSuccess(settled.unsafeToFuture())(status =>
                complete(status -> Map("status" -> ResendVerificationMessage))
              )
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
    /** Returns the identity of the currently authenticated player, with their email-verification flag.
      *
      * Validates the Bearer token, decodes it into a `UserContext`, and looks up the account's verification state.
      *
      * - Auth: Bearer token required
      * - 200: `WhoamiResponse` — the player's `id` (UUID), `name` (String), and `verified` (Boolean)
      * - 401: missing token, invalid or expired token, undecodable payload, or malformed player ID
      *
      * `verified` is `false` for an account that can't be found, which for a validly-signed token means it was deleted.
      */
    path("whoami") {
      authenticator.authenticatePlayer { player =>
        get {
          val verified = userRepo.findById(player.id).map(_.exists(_.emailVerifiedAt.isDefined))
          onSuccess(verified.unsafeToFuture())(v => complete(WhoamiResponse(player.id, player.name, v)))
        }
      }
    }
  }
}
