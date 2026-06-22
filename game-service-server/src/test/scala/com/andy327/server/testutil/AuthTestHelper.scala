package com.andy327.server.testutil

import com.andy327.server.auth.{JwtIssuer, UserContext}
import com.andy327.server.lobby.Player

/** Utility object for creating JWT tokens in tests.
  *
  * Mints tokens directly for an arbitrary `Player`, bypassing registration/login — the way tests act as players
  * without going through credentialed auth. Tokens are issued via the production [[JwtIssuer]] (same secret, algorithm,
  * and iat/exp claims), so they match what real authentication produces.
  */
object AuthTestHelper {

  private val jwtIssuer = JwtIssuer.fromConfig()

  /** Generates a signed JWT for a given Player, suitable for use in test Authorization headers.
    *
    * @param player The player for whom to generate a token (includes ID and name)
    * @return A signed JWT token as a String (e.g., "eyJhbGciOiJIUzI1NiIsInR5cCI6...")
    */
  def createTestToken(player: Player): String =
    jwtIssuer.issue(UserContext(player.id.toString, player.name))
}
