package com.andy327.server.http.routes

import cats.effect.unsafe.implicits.global

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.persistence.db.InMemoryUserRepository
import com.andy327.server.auth.{PasswordHasher, PasswordIdentityProvider}
import com.andy327.server.http.auth.{ChangePasswordRequest, LoginRequest, RegisterRequest, WhoamiResponse}
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  // Fresh in-memory accounts per test; small Argon2 parameters keep hashing fast. The routes share the account store
  // with the identity provider, so lookups such as whoami's verification flag see the accounts registration created.
  private def newRoutes: Route = {
    val userRepo = new InMemoryUserRepository
    new AuthRoutes(new PasswordIdentityProvider(userRepo, new PasswordHasher(256, 1, 1)), userRepo = userRepo).routes
  }

  /** Decodes a flat string-valued JSON object response, e.g. `{"token":"..."}` or `{"id":..,"name":..}`. */
  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def registerEntity(req: RegisterRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)
  private def loginEntity(req: LoginRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)
  private def changePasswordEntity(req: ChangePasswordRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)

  /** Registers an account and returns its bearer token. */
  private def registerAndToken(routes: Route, req: RegisterRequest): String =
    Post("/auth/register", registerEntity(req)) ~> routes ~> check {
      status shouldBe StatusCodes.Created
      fieldsOf(responseAs[String])("token")
    }

  "AuthRoutes POST /auth/register" should {
    "create an account and return a JWT with 201" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "s3cretpw"))) ~> routes ~>
      check {
        status shouldBe StatusCodes.Created
        fieldsOf(responseAs[String])("token").length should be > 10
      }
    }

    "reject a duplicate email with 409" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "passw0rd1"))) ~> routes ~>
      check {
        status shouldBe StatusCodes.Created
      }
      Post("/auth/register", registerEntity(RegisterRequest("alice2", "alice@example.com", "passw0rd2"))) ~> routes ~>
      check {
        status shouldBe StatusCodes.Conflict
        fieldsOf(responseAs[String])("error") should include("already registered")
      }
    }

    "reject a body missing required fields with 400" in {
      val entity = HttpEntity(ContentTypes.`application/json`, "{}")
      Post("/auth/register", entity) ~> Route.seal(newRoutes) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "reject invalid field values with 400 and a message" in
      Post("/auth/register", registerEntity(RegisterRequest("alice", "not-an-email", "s3cret77"))) ~> newRoutes ~>
      check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("valid address")
      }

    "trim surrounding whitespace so the account logs in by its trimmed email" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest(" alice ", " alice@example.com ", "s3cret77"))) ~>
      routes ~> check(status shouldBe StatusCodes.Created)

      Post("/auth/token", loginEntity(LoginRequest("alice@example.com", "s3cret77"))) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }
    }
  }

  "AuthRoutes POST /auth/token" should {
    "return a JWT for correct credentials" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "s3cretpw"))) ~> routes ~>
      check(status shouldBe StatusCodes.Created)

      Post("/auth/token", loginEntity(LoginRequest("alice@example.com", "s3cretpw"))) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        fieldsOf(responseAs[String])("token").length should be > 10
      }
    }

    "reject a wrong password with 401" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "s3cretpw"))) ~> routes ~>
      check(status shouldBe StatusCodes.Created)

      Post("/auth/token", loginEntity(LoginRequest("alice@example.com", "wrong"))) ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
        fieldsOf(responseAs[String])("error") should include("Invalid email or password")
      }
    }

    "reject an unknown email with 401" in
      Post("/auth/token", loginEntity(LoginRequest("nobody@example.com", "whatever"))) ~> newRoutes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }

    "reject a blank login field with 400" in
      Post("/auth/token", loginEntity(LoginRequest("", "whatever"))) ~> newRoutes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("must not be blank")
      }
  }

  "AuthRoutes POST /auth/password" should {
    "change the password (204) so the new one logs in and the old one no longer does" in {
      val routes = newRoutes
      val token = registerAndToken(routes, RegisterRequest("alice", "alice@example.com", "oldpw123"))

      Post("/auth/password", changePasswordEntity(ChangePasswordRequest("oldpw123", "newpw456")))
        .withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        status shouldBe StatusCodes.NoContent
      }

      Post("/auth/token", loginEntity(LoginRequest("alice@example.com", "newpw456"))) ~> routes ~>
      check(status shouldBe StatusCodes.OK)
      Post("/auth/token", loginEntity(LoginRequest("alice@example.com", "oldpw123"))) ~> routes ~>
      check(status shouldBe StatusCodes.Unauthorized)
    }

    "reject a wrong current password with 403" in {
      val routes = newRoutes
      val token = registerAndToken(routes, RegisterRequest("alice", "alice@example.com", "oldpw123"))

      Post("/auth/password", changePasswordEntity(ChangePasswordRequest("wrongpw1", "newpw456")))
        .withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        status shouldBe StatusCodes.Forbidden
        fieldsOf(responseAs[String])("error") should include("incorrect")
      }
    }

    "reject an out-of-range new password with 400" in {
      val routes = newRoutes
      val token = registerAndToken(routes, RegisterRequest("alice", "alice@example.com", "oldpw123"))

      Post("/auth/password", changePasswordEntity(ChangePasswordRequest("oldpw123", "short")))
        .withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("at least 8")
      }
    }

    "reject an unauthenticated change with 401" in
      Post("/auth/password", changePasswordEntity(ChangePasswordRequest("oldpw123", "newpw456"))) ~> newRoutes ~>
      check {
        status shouldBe StatusCodes.Unauthorized
      }
  }

  "AuthRoutes GET /auth/whoami" should {
    "return the authenticated player's identity for a valid token" in {
      val routes = newRoutes
      val token =
        Post(
          "/auth/register",
          registerEntity(RegisterRequest("bob", "bob@example.com", "s3cretpw"))
        ) ~> routes ~> check {
          status shouldBe StatusCodes.Created
          fieldsOf(responseAs[String])("token")
        }

      Get("/auth/whoami").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val whoami = decode[WhoamiResponse](responseAs[String]).getOrElse(fail("expected a WhoamiResponse"))
        whoami.name shouldBe "bob"
        whoami.id.toString.length should be > 10
        whoami.verified shouldBe false // a freshly registered account is unverified
      }
    }

    "return 401 when the Authorization header is missing" in
      Get("/auth/whoami") ~> newRoutes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
  }
}
