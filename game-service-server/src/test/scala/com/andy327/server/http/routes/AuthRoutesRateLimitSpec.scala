package com.andy327.server.http.routes

import scala.concurrent.duration._

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
import com.andy327.server.auth.{InMemoryAuthRateLimiter, PasswordHasher, PasswordIdentityProvider}
import com.andy327.server.config.AuthRateLimitConfig
import com.andy327.server.http.auth.{LoginRequest, RegisterRequest}
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesRateLimitSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  /** Generous IP limit so the per-account lockout can be exercised without the IP throttle firing first. */
  private def lockoutConfig(ipMax: Int = 100, threshold: Int = 3): AuthRateLimitConfig =
    AuthRateLimitConfig(
      enabled = true,
      ipWindow = 1.minute,
      ipMaxAttempts = ipMax,
      lockoutThreshold = threshold,
      lockoutWindow = 15.minutes,
      lockoutDuration = 15.minutes
    )

  private def newRoutes(config: AuthRateLimitConfig): Route =
    new AuthRoutes(
      new PasswordIdentityProvider(new InMemoryUserRepository, new PasswordHasher(256, 1, 1)),
      new InMemoryAuthRateLimiter,
      config
    ).routes

  private def registerEntity(req: RegisterRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)
  private def loginEntity(req: LoginRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)
  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def forwardedFor(ip: String) = RawHeader("X-Forwarded-For", ip)

  "AuthRoutes per-IP throttle" should {
    "admit requests up to the limit, then reject with 429 and a Retry-After header" in {
      val routes = newRoutes(lockoutConfig(ipMax = 2))
      def register(n: Int) =
        Post("/auth/register", registerEntity(RegisterRequest(s"alice$n", s"alice$n@example.com", "s3cretpw")))
          .withHeaders(forwardedFor("1.2.3.4"))

      register(1) ~> routes ~> check(status shouldBe StatusCodes.Created)
      register(2) ~> routes ~> check(status shouldBe StatusCodes.Created)
      register(3) ~> routes ~> check {
        status shouldBe StatusCodes.TooManyRequests
        header("Retry-After") shouldBe defined
        fieldsOf(responseAs[String])("error") should include("Too many requests")
      }
    }

    "count each source IP independently" in {
      val routes = newRoutes(lockoutConfig(ipMax = 1))
      def register(n: Int, ip: String) =
        Post("/auth/register", registerEntity(RegisterRequest(s"bob$n", s"bob$n@example.com", "s3cretpw")))
          .withHeaders(forwardedFor(ip))

      register(1, "10.0.0.1") ~> routes ~> check(status shouldBe StatusCodes.Created)
      register(2, "10.0.0.2") ~> routes ~> check(status shouldBe StatusCodes.Created) // different IP, own bucket
      register(3, "10.0.0.1") ~> routes ~> check(status shouldBe StatusCodes.TooManyRequests)
    }
  }

  "AuthRoutes per-account lockout" should {
    "lock an account after the failed-login threshold, rejecting even correct credentials with 429" in {
      val routes = newRoutes(lockoutConfig(threshold = 3))
      Post("/auth/register", registerEntity(RegisterRequest("carol", "carol@example.com", "correctpw"))) ~> routes ~>
      check(status shouldBe StatusCodes.Created)

      def badLogin() =
        Post("/auth/token", loginEntity(LoginRequest("carol@example.com", "wrongpw"))) ~> routes ~> check {
          status shouldBe StatusCodes.Unauthorized
        }
      badLogin()
      badLogin()
      badLogin() // third failure trips the lock

      // Even the correct password is now refused while the lock is in force.
      Post("/auth/token", loginEntity(LoginRequest("carol@example.com", "correctpw"))) ~> routes ~> check {
        status shouldBe StatusCodes.TooManyRequests
        header("Retry-After") shouldBe defined
        fieldsOf(responseAs[String])("error") should include("locked")
      }
    }

    "reset the failure count on a successful login so it does not accumulate across sessions" in {
      val routes = newRoutes(lockoutConfig(threshold = 3))
      Post("/auth/register", registerEntity(RegisterRequest("dave", "dave@example.com", "correctpw"))) ~> routes ~>
      check(status shouldBe StatusCodes.Created)

      def badLogin() =
        Post("/auth/token", loginEntity(LoginRequest("dave@example.com", "wrongpw"))) ~> routes ~>
        check(status shouldBe StatusCodes.Unauthorized)
      def goodLogin() =
        Post("/auth/token", loginEntity(LoginRequest("dave@example.com", "correctpw"))) ~> routes ~>
        check(status shouldBe StatusCodes.OK)

      badLogin()
      badLogin()
      goodLogin() // clears the two failures
      badLogin()
      badLogin() // only two failures since the reset — still under the threshold
      goodLogin() // so a correct login still succeeds rather than being locked out
    }
  }
}
