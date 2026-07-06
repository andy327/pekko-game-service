package com.andy327.server.http.auth

import java.time.Instant

import scala.concurrent.duration._

import cats.effect.unsafe.implicits.global

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.lobby.Player
import com.andy327.server.auth.{InMemoryRevocationStore, JwtIssuer, UserContext}

class JwtAuthenticatorRevocationSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private val issuer = JwtIssuer.fromConfig()

  /** Mints a token whose `iat` is "now" for a fresh player, returning both. */
  private def tokenForNewPlayer(name: String): (Player, String) = {
    val player = Player(name)
    (player, issuer.issue(UserContext(player.id.toString, player.name)))
  }

  private def routeWith(store: InMemoryRevocationStore): Route =
    new JwtAuthenticator(revocationStore = store).authenticatePlayer(player => complete(player.name))

  private def get(token: String, route: Route) =
    Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> route

  "JwtAuthenticator revocation" should {
    "reject a token issued before the account's revocation cutoff" in {
      val store = new InMemoryRevocationStore
      val (player, token) = tokenForNewPlayer("frank")
      // Cutoff comfortably after the token's issued-at, so the token predates it.
      store.revokeBefore(player.id, Instant.now().plusSeconds(60), 1.hour).unsafeRunSync()

      get(token, routeWith(store)) ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Token has been revoked")
      }
    }

    "accept a token issued after the account's revocation cutoff" in {
      val store = new InMemoryRevocationStore
      val (player, token) = tokenForNewPlayer("grace")
      // Cutoff well before the token's issued-at, so this token survives it.
      store.revokeBefore(player.id, Instant.now().minusSeconds(60), 1.hour).unsafeRunSync()

      get(token, routeWith(store)) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "grace"
      }
    }

    "accept any token when nothing is revoked" in {
      val store = new InMemoryRevocationStore
      val (_, token) = tokenForNewPlayer("heidi")

      get(token, routeWith(store)) ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe "heidi"
      }
    }
  }
}
