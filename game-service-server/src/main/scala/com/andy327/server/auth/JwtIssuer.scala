package com.andy327.server.auth

import java.time.Clock

import scala.concurrent.duration.FiniteDuration

import io.circe.syntax._
import pdi.jwt.{JwtAlgorithm, JwtCirce, JwtClaim}

import com.andy327.server.config.JwtConfig

/** Mints signed JWTs for an authenticated identity.
  *
  * The encoded token carries the `UserContext` as its content alongside `iat`/`exp` claims spaced `ttl` apart, so it
  * stops being accepted once expired. The `Clock` is injectable to make expiry deterministic in tests.
  */
class JwtIssuer(secret: String, ttl: FiniteDuration)(implicit clock: Clock = Clock.systemUTC()) {

  /** Encodes a signed, time-limited token for the given user. */
  def issue(user: UserContext): String = {
    val claim = JwtClaim(content = user.asJson.noSpaces).issuedNow.expiresIn(ttl.toSeconds)
    JwtCirce.encode(claim, secret, JwtAlgorithm.HS256)
  }
}

object JwtIssuer {

  /** Builds an issuer from the configured JWT secret and TTL. */
  def fromConfig(): JwtIssuer = new JwtIssuer(JwtConfig.secretKey, JwtConfig.ttl)
}
