package com.andy327.server.http.routes

import java.time.{Clock, Instant, ZoneOffset}

import cats.effect.unsafe.implicits.global

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.lobby.Player
import com.andy327.persistence.db.InMemoryUserRepository
import com.andy327.server.auth.{
  InMemoryRevocationStore,
  JwtIssuer,
  PasswordHasher,
  PasswordIdentityProvider,
  UserContext
}
import com.andy327.server.config.JwtConfig
import com.andy327.server.http.auth.{ChangePasswordRequest, JwtAuthenticator, RegisterRequest, WhoamiResponse}
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesRevocationSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private def newSetup(): (Route, InMemoryRevocationStore) = {
    val store = new InMemoryRevocationStore
    val authenticator = new JwtAuthenticator(revocationStore = store)
    val routes = new AuthRoutes(
      new PasswordIdentityProvider(new InMemoryUserRepository, new PasswordHasher(256, 1, 1)),
      authenticator = authenticator,
      revocationStore = store
    ).routes
    (routes, store)
  }

  /** A token whose `iat` is 10 seconds in the past, so a cutoff written "now" strictly post-dates it. */
  private def pastToken(player: Player): String = {
    val past = Clock.fixed(Instant.now().minusSeconds(10), ZoneOffset.UTC)
    new JwtIssuer(JwtConfig.secretKey, JwtConfig.ttl)(past).issue(UserContext(player.id.toString, player.name))
  }

  private def bearer(token: String) = RawHeader("Authorization", s"Bearer $token")
  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def registerEntity(req: RegisterRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)
  private def changePasswordEntity(req: ChangePasswordRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, req.asJson.noSpaces)

  "AuthRoutes POST /auth/logout" should {
    "revoke the caller's outstanding tokens so a previously-valid token is then rejected" in {
      val (routes, _) = newSetup()
      val player = Player("eve")
      val token = pastToken(player)

      Get("/auth/whoami").withHeaders(bearer(token)) ~> routes ~> check(status shouldBe StatusCodes.OK)

      Post("/auth/logout").withHeaders(bearer(token)) ~> routes ~> check(status shouldBe StatusCodes.NoContent)

      Get("/auth/whoami").withHeaders(bearer(token)) ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Token has been revoked")
      }
    }

    "reject an unauthenticated logout with 401" in
      Post("/auth/logout") ~> newSetup()._1 ~> check(status shouldBe StatusCodes.Unauthorized)
  }

  "AuthRoutes POST /auth/password" should {
    "record a revocation cutoff for the account on a successful change" in {
      val (routes, store) = newSetup()
      val token =
        Post("/auth/register", registerEntity(RegisterRequest("ivan", "ivan@example.com", "oldpw123"))) ~> routes ~>
        check {
          status shouldBe StatusCodes.Created
          fieldsOf(responseAs[String])("token")
        }
      val id = Get("/auth/whoami").withHeaders(bearer(token)) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        decode[WhoamiResponse](responseAs[String]).getOrElse(fail("expected a WhoamiResponse")).id
      }

      store.revokedBefore(id).unsafeRunSync() shouldBe None

      Post("/auth/password", changePasswordEntity(ChangePasswordRequest("oldpw123", "newpw456")))
        .withHeaders(bearer(token)) ~> routes ~> check(status shouldBe StatusCodes.NoContent)

      store.revokedBefore(id).unsafeRunSync() shouldBe defined
    }
  }
}
