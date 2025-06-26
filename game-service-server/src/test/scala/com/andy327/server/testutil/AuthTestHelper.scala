package com.andy327.server.testutil

import io.circe.syntax._
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import com.andy327.server.auth.UserContext
import com.andy327.server.config.JwtConfig
import com.andy327.server.lobby.Player

/**
 * Utility object for creating JWT tokens in tests.
 *
 * This helps simulate authenticated requests by generating tokens that encode a Player's identity, signed using the
 * same secret and algorithm as in production.
 */
object AuthTestHelper {

  /**
   * Generates a JWT token for a given Player, suitable for use in test Authorization headers.
   *
   * @param player The player for whom to generate a token (includes ID and name)
   * @return A signed JWT token as a String (e.g., "eyJhbGciOiJIUzI1NiIsInR5cCI6...")
   */
  def createTestToken(player: Player): String = {
    val user = UserContext(player.id.toString, player.name)
    JwtCirce.encode(user.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
  }
}
