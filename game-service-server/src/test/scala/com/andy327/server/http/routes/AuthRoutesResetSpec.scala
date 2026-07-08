package com.andy327.server.http.routes

import java.time.{Clock, Instant, ZoneId, ZoneOffset}

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.persistence.db.InMemoryUserRepository
import com.andy327.server.auth.{
  EmailKind,
  EmailSender,
  InMemoryEmailSender,
  InMemoryRevocationStore,
  InMemorySingleUseTokenStore,
  PasswordHasher,
  PasswordIdentityProvider
}
import com.andy327.server.config.AuthResetConfig
import com.andy327.server.http.auth.{
  ForgotPasswordRequest,
  JwtAuthenticator,
  LoginRequest,
  RegisterRequest,
  ResetPasswordRequest
}
import com.andy327.server.http.json.JsonProtocol._

/** A [[Clock]] the test can advance, so reset-token expiry is deterministic. */
private class AdvanceableClock(start: Instant) extends Clock {
  @volatile private var current: Instant = start
  def advance(by: FiniteDuration): Unit = current = current.plusMillis(by.toMillis)
  override def instant(): Instant = current
  override def getZone: ZoneId = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this
}

class AuthRoutesResetSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private case class Setup(
      routes: Route,
      userRepo: InMemoryUserRepository,
      emailSender: InMemoryEmailSender,
      revocationStore: InMemoryRevocationStore
  )

  private def newSetup(clock: Clock = Clock.systemUTC(), tokenTtl: FiniteDuration = 1.hour): Setup = {
    val userRepo = new InMemoryUserRepository
    val identity = new PasswordIdentityProvider(userRepo, new PasswordHasher(256, 1, 1))
    val emailSender = new InMemoryEmailSender
    val tokenStore = new InMemorySingleUseTokenStore()(clock)
    val revocationStore = new InMemoryRevocationStore
    val authenticator = new JwtAuthenticator(revocationStore = revocationStore)
    val routes = new AuthRoutes(
      identity,
      authenticator = authenticator,
      revocationStore = revocationStore,
      userRepo = userRepo,
      emailSender = emailSender,
      tokenStore = tokenStore,
      resetConfig = AuthResetConfig(tokenTtl)
    ).routes
    Setup(routes, userRepo, emailSender, revocationStore)
  }

  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def entityFor[A: io.circe.Encoder](a: A): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, a.asJson.noSpaces)

  /** Registers an account. */
  private def register(setup: Setup, req: RegisterRequest): Unit =
    Post("/auth/register", entityFor(req)) ~> setup.routes ~> check(status shouldBe StatusCodes.Created)

  /** Runs forgot-password for `email` and returns the raw reset token pulled from the sent email, if any. */
  private def forgotAndToken(setup: Setup, email: String): Option[String] = {
    Post("/auth/forgot-password", entityFor(ForgotPasswordRequest(email))) ~> setup.routes ~> check {
      status shouldBe StatusCodes.Accepted
    }
    setup.emailSender.sent.unsafeRunSync().lastOption.map(_.token)
  }

  "AuthRoutes POST /auth/forgot-password" should {
    "email a reset token to a registered address and answer 202" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))

      Post("/auth/forgot-password", entityFor(ForgotPasswordRequest("alice@example.com"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.Accepted
      }

      // Registration also emails a verification link, so filter to the reset email this endpoint sent.
      val sent = setup.emailSender.sent.unsafeRunSync().filter(_.kind == EmailKind.PasswordReset)
      sent should have size 1
      sent.head.to shouldBe "alice@example.com"
      sent.head.token should not be empty
    }

    "answer 202 for an unknown address without sending anything (no enumeration signal)" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))

      val known = Post("/auth/forgot-password", entityFor(ForgotPasswordRequest("alice@example.com"))) ~>
        setup.routes ~> check { status shouldBe StatusCodes.Accepted; responseAs[String] }
      val unknown = Post("/auth/forgot-password", entityFor(ForgotPasswordRequest("nobody@example.com"))) ~>
        setup.routes ~> check { status shouldBe StatusCodes.Accepted; responseAs[String] }

      // Identical response body for registered and unregistered, and a reset email only for the registered address.
      unknown shouldBe known
      setup.emailSender.sent.unsafeRunSync().filter(_.kind == EmailKind.PasswordReset).map(_.to) shouldBe
        Vector("alice@example.com")
    }

    "answer 202 for a blank email without attempting a lookup or send" in {
      val setup = newSetup()
      Post("/auth/forgot-password", entityFor(ForgotPasswordRequest("   "))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.Accepted
      }
      setup.emailSender.sent.unsafeRunSync() shouldBe empty
    }

    "still answer 202 when the email sender fails, so an outage can't leak account existence" in {
      val failingSender = new EmailSender {
        override def sendPasswordReset(to: String, token: String): IO[Unit] =
          IO.raiseError(new RuntimeException("email provider down"))
        override def sendEmailVerification(to: String, token: String): IO[Unit] = IO.unit
      }
      val userRepo = new InMemoryUserRepository
      val identity = new PasswordIdentityProvider(userRepo, new PasswordHasher(256, 1, 1))
      val routes = new AuthRoutes(identity, userRepo = userRepo, emailSender = failingSender).routes

      Post("/auth/register", entityFor(RegisterRequest("alice", "alice@example.com", "oldpw123"))) ~> routes ~>
      check(status shouldBe StatusCodes.Created)
      Post("/auth/forgot-password", entityFor(ForgotPasswordRequest("alice@example.com"))) ~> routes ~> check {
        status shouldBe StatusCodes.Accepted
      }
    }
  }

  "AuthRoutes POST /auth/reset-password" should {
    "set a new password (204) so the new one logs in and the old one no longer does" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))
      val token = forgotAndToken(setup, "alice@example.com").getOrElse(fail("expected a reset token"))

      Post("/auth/reset-password", entityFor(ResetPasswordRequest(token, "newpw456"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      Post("/auth/token", entityFor(LoginRequest("alice@example.com", "newpw456"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.OK)
      Post("/auth/token", entityFor(LoginRequest("alice@example.com", "oldpw123"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.Unauthorized)
    }

    "advance the account's revocation cutoff on a successful reset" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))
      val id = setup.userRepo.findByEmail("alice@example.com").unsafeRunSync().get.id
      val token = forgotAndToken(setup, "alice@example.com").getOrElse(fail("expected a reset token"))

      setup.revocationStore.revokedBefore(id).unsafeRunSync() shouldBe None

      Post("/auth/reset-password", entityFor(ResetPasswordRequest(token, "newpw456"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.NoContent)

      setup.revocationStore.revokedBefore(id).unsafeRunSync() shouldBe defined
    }

    "reject a token that has already been used (single-use) with 400" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))
      val token = forgotAndToken(setup, "alice@example.com").getOrElse(fail("expected a reset token"))

      Post("/auth/reset-password", entityFor(ResetPasswordRequest(token, "newpw456"))) ~> setup.routes ~>
      check(status shouldBe StatusCodes.NoContent)
      Post("/auth/reset-password", entityFor(ResetPasswordRequest(token, "evennewer7"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Invalid or expired")
      }
    }

    "reject an unknown token with 400" in {
      val setup = newSetup()
      Post("/auth/reset-password", entityFor(ResetPasswordRequest("not-a-real-token", "newpw456"))) ~> setup.routes ~>
      check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Invalid or expired")
      }
    }

    "reject an expired token with 400" in {
      val clock = new AdvanceableClock(Instant.parse("2026-07-08T12:00:00Z"))
      val setup = newSetup(clock = clock, tokenTtl = 30.minutes)
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))
      val token = forgotAndToken(setup, "alice@example.com").getOrElse(fail("expected a reset token"))

      clock.advance(31.minutes)

      Post("/auth/reset-password", entityFor(ResetPasswordRequest(token, "newpw456"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "reject a blank token with 400 before consuming anything" in {
      val setup = newSetup()
      Post("/auth/reset-password", entityFor(ResetPasswordRequest("", "newpw456"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Token must not be blank")
      }
    }

    "reject an out-of-range new password with 400" in {
      val setup = newSetup()
      register(setup, RegisterRequest("alice", "alice@example.com", "oldpw123"))
      val token = forgotAndToken(setup, "alice@example.com").getOrElse(fail("expected a reset token"))

      Post("/auth/reset-password", entityFor(ResetPasswordRequest(token, "short"))) ~> setup.routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("at least 8")
      }
    }
  }
}
