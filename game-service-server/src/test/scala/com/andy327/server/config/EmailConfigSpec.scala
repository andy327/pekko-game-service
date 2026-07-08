package com.andy327.server.config

import com.typesafe.config.ConfigFactory

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class EmailConfigSpec extends AnyWordSpec with Matchers {

  "EmailConfig.fromConfig" should {
    "read every field from the email stanza" in {
      val config = ConfigFactory.parseString("""
        email {
          provider = "resend"
          from = "no-reply@example.com"
          base-url = "https://app.example.com"
          resend { api-key = "secret" }
        }
      """)

      EmailConfig.fromConfig(config) shouldBe EmailConfig(
        provider = "resend",
        from = "no-reply@example.com",
        baseUrl = "https://app.example.com",
        resendApiKey = "secret"
      )
    }

    "load the packaged application.conf defaults (no-op provider)" in {
      EmailConfig.fromConfig().provider shouldBe "noop"
    }
  }
}
