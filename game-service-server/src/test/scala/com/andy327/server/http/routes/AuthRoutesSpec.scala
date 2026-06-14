package com.andy327.server.http.routes

import io.circe.parser.decode
import io.circe.syntax._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.http.json.JsonProtocol._

class AuthRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val routes: Route = new AuthRoutes().routes

  /** Decodes a flat string-valued JSON object response, e.g. `{"token":"..."}` or `{"id":..,"name":..}`. */
  private def fieldsOf(body: String): Map[String, String] =
    decode[Map[String, String]](body).fold(err => fail(s"not a JSON object of strings: $err"), identity)

  private def jsonEntity(request: PlayerRequest): HttpEntity.Strict =
    HttpEntity(ContentTypes.`application/json`, request.asJson.noSpaces)

  "AuthRoutes" should {
    "return a JWT token for valid PlayerRequest without ID" in
      Post("/auth/token", jsonEntity(PlayerRequest(None, "alice"))) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val fields = fieldsOf(responseAs[String])
        fields.keys should contain("token")
        fields("token").length should be > 10
      }

    "return a JWT token for valid PlayerRequest with valid UUID" in
      Post("/auth/token", jsonEntity(PlayerRequest(Some("123e4567-e89b-12d3-a456-426614174000"), "alice"))) ~>
      routes ~> check {
        status shouldBe StatusCodes.OK
        fieldsOf(responseAs[String])("token") should not be empty
      }

    "reject PlayerRequest with malformed UUID" in
      Post("/auth/token", jsonEntity(PlayerRequest(Some("not-a-uuid"), "alice"))) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        fieldsOf(responseAs[String])("error") should include("Invalid UUID format")
      }

    "return player identity for valid token on /auth/whoami" in
      Post("/auth/token", jsonEntity(PlayerRequest(None, "bob"))) ~> routes ~> check {
        val token = fieldsOf(responseAs[String])("token")

        Get("/auth/whoami").withHeaders(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val whoami = fieldsOf(responseAs[String])
          whoami("name") shouldBe "bob"
          whoami("id").length should be > 10
        }
      }

    "return 401 if Authorization header is missing on /auth/whoami" in
      Get("/auth/whoami") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }

    "reject a JSON body that is not a valid PlayerRequest with 400" in {
      // valid JSON, but missing the required `name` field — the Circe unmarshaller fails the decode,
      // which the `entity` directive surfaces as a MalformedRequestContentRejection (400 once sealed)
      val entity = HttpEntity(ContentTypes.`application/json`, "{}")
      Post("/auth/token", entity) ~> Route.seal(routes) ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }
  }
}
