package com.andy327.server.http.routes

import java.time.{Clock, Instant}

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.persistence.db.{Account, InMemoryUserRepository}
import com.andy327.server.auth.{
  EmailKind,
  EmailSender,
  InMemoryEmailSender,
  InMemorySingleUseTokenStore,
  PasswordHasher,
  PasswordIdentityProvider,
  SentEmail
}
import com.andy327.server.config.AuthVerificationConfig
import com.andy327.server.http.auth.{RegisterRequest, ResendVerificationRequest, VerifyEmailRequest, WhoamiResponse}
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesVerifySpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private case class Setup(routes: Route, userRepo: InMemoryUserRepository, emailSender: InMemoryEmailSender)

  private def newSetup(clock: Clock = Clock.systemUTC(), verificationTtl: FiniteDuration = 24.hours): Setup = {
    val userRepo = new InMemoryUserRepository
    val identity = new PasswordIdentityProvider(userRepo, new PasswordHasher(256, 1, 1))
    val emailSender = new InMemoryEmailSender
    val tokenStore = new InMemorySingleUseTokenStore()(clock)
    val routes = new AuthRoutes(
      identity,
      userRepo = userRepo,
      emailSender = emailSender,
      tokenStore = tokenStore,
      verificationConfig = AuthVerificationConfig(verificationTtl)
    ).routes
    Setup(routes, userRepo, emailSender)
  }

  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def entityFor[A: io.circe.Encoder](a: A): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, a.asJson.noSpaces)

  /** Registers an account and returns its bearer token. */
  private def register(setup: Setup, req: RegisterRequest): String =
    Post("/auth/register", entityFor(req)) ~> setup.routes ~> check {
      status shouldBe StatusCodes.Created
      fieldsOf(responseAs[String])("token")
    }

  private def whoami(setup: Setup, jwt: String): WhoamiResponse =
    Get("/auth/whoami").withHeaders(RawHeader("Authorization", s"Bearer $jwt")) ~> setup.routes ~> check {
      status shouldBe StatusCodes.OK
      decode[WhoamiResponse](responseAs[String]).getOrElse(fail("expected a WhoamiResponse"))
    }

  private def verificationEmails(setup: Setup): Vector[SentEmail] =
    setup.emailSender.sent.unsafeRunSync().filter(_.kind == EmailKind.EmailVerification)

  private def lastVerificationToken(setup: Setup): String =
    verificationEmails(setup).lastOption.map(_.token).getOrElse(fail("expected a verification email"))

  "AuthRoutes POST /auth/register" should {
    "send a verification email to the new account" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw"))

      val sent = verificationEmails(setup)
      sent should have size 1
      sent.head.to shouldBe "alice@example.com"
      sent.head.token should not be empty
    }

    "still return 201 when the verification email fails to send" in {
      val failingSender = new EmailSender {
        override def sendPasswordReset(to: String, token: String): IO[Unit] = IO.unit
        override def sendEmailVerification(to: String, token: String): IO[Unit] =
          IO.raiseError(new RuntimeException("email provider down"))
      }
      val userRepo = new InMemoryUserRepository
      val identity = new PasswordIdentityProvider(userRepo, new PasswordHasher(256, 1, 1))
      val routes = new AuthRoutes(identity, userRepo = userRepo, emailSender = failingSender).routes

      Post("/auth/register", entityFor(RegisterRequest("alice", "alice@example.com", "s3cretpw"))) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
    }
  }

  "AuthRoutes POST /auth/verify" should {
    "verify the address (204) so whoami then reports the account verified" in {
      val setup = newSetup()
      val jwt = register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw"))
      whoami(setup, jwt).verified shouldBe false

      Post("/auth/verify", entityFor(VerifyEmailRequest(lastVerificationToken(setup)))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      whoami(setup, jwt).verified shouldBe true
    }

    "succeed for a second valid token on an already-verified account (idempotent)" in {
      val setup = newSetup()
      val jwt = register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw"))
      // Mint a second token while still unverified, so we have two valid tokens to redeem.
      Post("/auth/verify/resend", entityFor(ResendVerificationRequest("alice@example.com"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.Accepted)
      val tokens = verificationEmails(setup).map(_.token)
      tokens should have size 2

      Post("/auth/verify", entityFor(VerifyEmailRequest(tokens.head))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.NoContent)
      // The account is now verified; redeeming the other still-valid token succeeds rather than erroring.
      Post("/auth/verify", entityFor(VerifyEmailRequest(tokens(1)))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.NoContent)

      whoami(setup, jwt).verified shouldBe true
    }

    "reject a token that has already been used (single-use) with 400" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw"))
      val token = lastVerificationToken(setup)

      Post("/auth/verify", entityFor(VerifyEmailRequest(token))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.NoContent)
      Post("/auth/verify", entityFor(VerifyEmailRequest(token))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Invalid or expired")
      }
    }

    "reject an unknown token with 400" in {
      val setup = newSetup()
      Post("/auth/verify", entityFor(VerifyEmailRequest("not-a-real-token"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Invalid or expired")
      }
    }

    "reject a blank token with 400" in {
      val setup = newSetup()
      Post("/auth/verify", entityFor(VerifyEmailRequest(""))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Token must not be blank")
      }
    }

    "reject an expired token with 400" in {
      val clock = new AdvanceableClock(Instant.parse("2026-07-08T12:00:00Z"))
      val setup = newSetup(clock = clock, verificationTtl = 30.minutes)
      register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw"))
      val token = lastVerificationToken(setup)

      clock.advance(31.minutes)

      Post("/auth/verify", entityFor(VerifyEmailRequest(token))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }

  "AuthRoutes POST /auth/verify/resend" should {
    "send a fresh verification email for a registered, unverified address (202)" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw")) // 1st email
      Post("/auth/verify/resend", entityFor(ResendVerificationRequest("alice@example.com"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.Accepted
      }
      verificationEmails(setup) should have size 2
    }

    "answer 202 without sending for unknown or already-verified addresses (no enumeration signal)" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "s3cretpw"))
      Post("/auth/verify", entityFor(VerifyEmailRequest(lastVerificationToken(setup)))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.NoContent)
      val countBefore = verificationEmails(setup).size

      Post("/auth/verify/resend", entityFor(ResendVerificationRequest("nobody@example.com"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.Accepted)
      Post("/auth/verify/resend", entityFor(ResendVerificationRequest("alice@example.com"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.Accepted)

      // Neither an unknown address nor an already-verified one triggers a new email.
      verificationEmails(setup).size shouldBe countBefore
    }

    "answer 202 for a blank email without sending" in {
      val setup = newSetup()
      Post("/auth/verify/resend", entityFor(ResendVerificationRequest("   "))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.Accepted
      }
      verificationEmails(setup) shouldBe empty
    }

    "still answer 202 when the account lookup fails, so an outage can't leak account existence" in {
      // sendVerification swallows its own send failures, so only a repository failure reaches the route's handler.
      val failingRepo = new InMemoryUserRepository {
        override def findByEmail(email: String): IO[Option[Account]] =
          IO.raiseError(new RuntimeException("account store down"))
      }
      val identity = new PasswordIdentityProvider(failingRepo, new PasswordHasher(256, 1, 1))
      val routes = new AuthRoutes(identity, userRepo = failingRepo, emailSender = new InMemoryEmailSender).routes

      Post("/auth/verify/resend", entityFor(ResendVerificationRequest("alice@example.com"))) ~> routes ~> check {
        status shouldBe StatusCodes.Accepted
      }
    }
  }
}
