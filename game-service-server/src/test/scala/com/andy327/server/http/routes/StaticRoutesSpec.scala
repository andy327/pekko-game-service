package com.andy327.server.http.routes

import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class StaticRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  val routes: Route = new StaticRoutes().routes

  "StaticRoutes" should {
    "serve the application shell at the root path" in
      Get("/") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType shouldBe ContentTypes.`text/html(UTF-8)`
        responseAs[String] should include("Pekko Game Service")
      }

    "serve the client script with a JavaScript content type" in
      Get("/app.js") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        mediaType.subType shouldBe "javascript"
        responseAs[String] should include("authenticate")
      }

    "reject an unknown asset path" in
      Get("/does-not-exist.js") ~> routes ~> check {
        handled shouldBe false
      }
  }
}
