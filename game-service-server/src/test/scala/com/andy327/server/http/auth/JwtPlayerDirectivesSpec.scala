package com.andy327.server.http.auth

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.{AuthorizationFailedRejection, Route}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import com.andy327.server.auth.UserContext
import com.andy327.server.config.JwtConfig
import com.andy327.server.lobby.Player

class JwtPlayerDirectivesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  val testRoute: Route =
    JwtPlayerDirectives.authenticatePlayer { player =>
      complete(s"Hello, ${player.name}!")
    }

  "JwtPlayerDirectives.authenticatePlayer" should {
    "reject if Authorization header is missing" in
      Get("/") ~> testRoute ~> check {
        rejections should contain(AuthorizationFailedRejection)
      }

    "reject if Authorization header is not a Bearer token" in
      Get("/").withHeaders(RawHeader("Authorization", "Token abc")) ~> testRoute ~> check {
        rejections should contain(AuthorizationFailedRejection)
      }

    "reject if JWT is invalid" in {
      val badToken = "invalid.token"
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $badToken")) ~> testRoute ~> check {
        rejections should contain(AuthorizationFailedRejection)
      }
    }

    "reject if JWT has invalid payload JSON" in {
      val badJson = "not-a-json".asJson.noSpaces
      val token = JwtCirce.encode(badJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> testRoute ~> check {
        rejections should contain(AuthorizationFailedRejection)
      }
    }

    "reject if Player.fromJWT fails" in {
      val userContext = UserContext(id = "not-a-uuid", name = "fake")
      val token = JwtCirce.encode(userContext.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
      Get("/").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> testRoute ~> check {
        rejections should contain(AuthorizationFailedRejection)
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
  }
}
