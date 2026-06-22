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
import com.andy327.server.http.auth.{LoginRequest, RegisterRequest}
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  // Fresh in-memory accounts per test; small Argon2 parameters keep hashing fast.
  private def newRoutes: Route =
    new AuthRoutes(new PasswordIdentityProvider(new InMemoryUserRepository, new PasswordHasher(256, 1, 1))).routes

  /** Decodes a flat string-valued JSON object response, e.g. `{"token":"..."}` or `{"id":..,"name":..}`. */
  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def registerEntity(req: RegisterRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)
  private def loginEntity(req: LoginRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)

  "AuthRoutes POST /auth/register" should {
    "create an account and return a JWT with 201" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "s3cret"))) ~> routes ~>
      check {
        status shouldBe StatusCodes.Created
        fieldsOf(responseAs[String])("token").length should be > 10
      }
    }

    "reject a duplicate email with 409" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "pw1"))) ~> routes ~> check {
        status shouldBe StatusCodes.Created
      }
      Post("/auth/register", registerEntity(RegisterRequest("alice2", "alice@example.com", "pw2"))) ~> routes ~> check {
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
  }

  "AuthRoutes POST /auth/token" should {
    "return a JWT for correct credentials" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "s3cret"))) ~> routes ~>
      check(status shouldBe StatusCodes.Created)

      Post("/auth/token", loginEntity(LoginRequest("alice@example.com", "s3cret"))) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        fieldsOf(responseAs[String])("token").length should be > 10
      }
    }

    "reject a wrong password with 401" in {
      val routes = newRoutes
      Post("/auth/register", registerEntity(RegisterRequest("alice", "alice@example.com", "s3cret"))) ~> routes ~>
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
  }

  "AuthRoutes GET /auth/whoami" should {
    "return the authenticated player's identity for a valid token" in {
      val routes = newRoutes
      val token =
        Post("/auth/register", registerEntity(RegisterRequest("bob", "bob@example.com", "s3cret"))) ~> routes ~> check {
          status shouldBe StatusCodes.Created
          fieldsOf(responseAs[String])("token")
        }

      Get("/auth/whoami").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val whoami = fieldsOf(responseAs[String])
        whoami("name") shouldBe "bob"
        whoami("id").length should be > 10
      }
    }

    "return 401 when the Authorization header is missing" in
      Get("/auth/whoami") ~> newRoutes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
  }
}
