package com.andy327.server.config

import com.typesafe.config.ConfigFactory

object JwtConfig {
  private val config = ConfigFactory.load()

  /**
   * The shared secret key used for signing and verifying JWT tokens. Must be defined in `application.conf` under
   * `jwt.secret`, for example:
   *
   * jwt {
   *   secret = "your-secret-key"
   * }
   */
  val secretKey: String = config.getString("jwt.secret")
}
