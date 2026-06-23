package com.andy327.server.auth

import java.time.{Clock, Instant, ZoneOffset}

import scala.concurrent.duration._

import io.circe.parser.decode
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import pdi.jwt.{JwtAlgorithm, JwtCirce}

class JwtIssuerSpec extends AnyWordSpec with Matchers {

  private val secret = "test-secret"
  private val algos = Seq(JwtAlgorithm.HS256)

  "JwtIssuer.issue" should {
    "encode a token that verifies and decodes back to the user context" in {
      val token = new JwtIssuer(secret, 1.hour).issue(UserContext("id-1", "alice"))

      val json = JwtCirce.decodeJson(token, secret, algos).get
      decode[UserContext](json.noSpaces) shouldBe Right(UserContext("id-1", "alice"))
    }

    "set iat and exp claims spaced by the configured ttl" in {
      val now = Instant.now()
      val token = new JwtIssuer(secret, 1.hour)(Clock.fixed(now, ZoneOffset.UTC)).issue(UserContext("id-1", "alice"))

      val claim = JwtCirce.decode(token, secret, algos).get
      claim.issuedAt shouldBe Some(now.getEpochSecond)
      claim.expiration shouldBe Some(now.getEpochSecond + 1.hour.toSeconds)
    }

    "produce a token that is rejected once expired" in {
      // Issue as if two hours ago with a one-hour ttl, so the token's exp is already in the past for the system clock.
      val twoHoursAgo = Clock.fixed(Instant.now().minusSeconds(2.hours.toSeconds), ZoneOffset.UTC)
      val token = new JwtIssuer(secret, 1.hour)(twoHoursAgo).issue(UserContext("id-1", "alice"))

      JwtCirce.decodeJson(token, secret, algos).isFailure shouldBe true
    }
  }
}
