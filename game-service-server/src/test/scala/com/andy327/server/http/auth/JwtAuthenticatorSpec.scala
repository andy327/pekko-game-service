package com.andy327.server.http.auth

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import com.andy327.actor.lobby.Player
import com.andy327.server.auth.UserContext
import com.andy327.server.config.JwtConfig

class JwtAuthenticatorSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  private val authenticator = new JwtAuthenticator()

  val testRoute: Route =
    authenticator.authenticatePlayer { player =>
      complete(s"Hello, ${player.name}!")
    }

  val queryParamRoute: Route =
    authenticator.authenticatePlayerAllowingQueryParam { player =>
      complete(s"Hello, ${player.name}!")
    }

  /** Encodes a valid JWT for a freshly-minted player and returns the token alongside the player. */
  private def tokenForNewPlayer(name: String): (Player, String) = {
    val player = Player(name)
    val userContext = UserContext(player.id.toString, player.name)
    (player, JwtCirce.encode(userContext.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256))
  }

  "JwtAuthenticator.authenticatePlayer" should {
    "reject if Authorization header is missing" in
      Get("/") ~> testRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Missing Authorization header")
      }

    "reject if Authorization header is not a Bearer token" in
      Get("/").withHeaders(RawHeader("Authorization", "Token abc")) ~> testRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Missing Authorization header")
      }

    "reject if JWT is invalid" in {
      val badToken = "invalid.token"
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $badToken")) ~> testRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Token is invalid or expired")
      }
    }

    "reject if JWT has invalid payload JSON" in {
      val badJson = "not-a-json".asJson.noSpaces
      val token = JwtCirce.encode(badJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> testRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Token payload could not be parsed")
      }
    }

    "reject if the player id is not a valid UUID" in {
      val userContext = UserContext(id = "not-a-uuid", name = "fake")
      val token = JwtCirce.encode(userContext.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> testRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Invalid player ID or name")
      }
    }

    "accept a valid JWT" in {
      val player = Player("alice")
      val userContext = UserContext(player.id.toString, player.name)
      val token = JwtCirce.encode(userContext.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)

      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> testRoute ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("alice")
      }
    }

    "ignore an access_token query parameter (header-only)" in {
      val (_, token) = tokenForNewPlayer("bob")
      Get(s"/?access_token=$token") ~> testRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Missing Authorization header")
      }
    }
  }

  "JwtAuthenticator.authenticatePlayerAllowingQueryParam" should {
    "accept a valid JWT from the access_token query parameter" in {
      val (_, token) = tokenForNewPlayer("carol")
      Get(s"/?access_token=$token") ~> queryParamRoute ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("carol")
      }
    }

    "still accept a valid JWT from the Authorization header" in {
      val (_, token) = tokenForNewPlayer("dave")
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> queryParamRoute ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] should include("dave")
      }
    }

    "reject when neither header nor query parameter supplies a token" in
      Get("/") ~> queryParamRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Missing access token")
      }

    "reject an invalid JWT supplied via the query parameter" in
      Get("/?access_token=invalid.token") ~> queryParamRoute ~> check {
        status shouldBe StatusCodes.Unauthorized
        responseAs[String] should include("Token is invalid or expired")
      }
  }
}
