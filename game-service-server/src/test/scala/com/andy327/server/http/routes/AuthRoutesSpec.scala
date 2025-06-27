package com.andy327.server.http.routes

import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val routes: Route = new AuthRoutes().routes

  "AuthRoutes" should {
    "return a JWT token for valid PlayerRequest without ID" in {
      val request = PlayerRequest(None, "alice")
      val entity = HttpEntity(ContentTypes.`application/json`, request.toJson.compactPrint)

      Post("/auth/token", entity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val json = responseAs[String].parseJson.asJsObject
        json.fields.keys should contain("token")
        json.fields("token").convertTo[String].length should be > 10
      }
    }

    "return a JWT token for valid PlayerRequest with valid UUID" in {
      val request = PlayerRequest(Some("123e4567-e89b-12d3-a456-426614174000"), "alice")
      val entity = HttpEntity(ContentTypes.`application/json`, request.toJson.compactPrint)

      Post("/auth/token", entity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val json = responseAs[String].parseJson.asJsObject
        json.fields("token").convertTo[String] should not be empty
      }
    }

    "reject PlayerRequest with malformed UUID" in {
      val request = PlayerRequest(Some("not-a-uuid"), "alice")
      val entity = HttpEntity(ContentTypes.`application/json`, request.toJson.compactPrint)

      Post("/auth/token", entity) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        val json = responseAs[String].parseJson.asJsObject
        json.fields("error").convertTo[String] should include("Invalid UUID format")
      }
    }
  }
}
