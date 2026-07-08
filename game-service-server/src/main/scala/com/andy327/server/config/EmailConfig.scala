package com.andy327.server.config

import com.typesafe.config.{Config, ConfigFactory}

/** Outbound-email configuration, read from the `email` stanza.
  *
  * @param provider which [[com.andy327.server.auth.EmailSender]] to wire: `"resend"` delivers via the Resend HTTP API,
  *   anything else (e.g. `"noop"`) drops messages — the default for local runs and CI without credentials
  * @param from the `From` address on sent mail
  * @param baseUrl origin the emailed links point back to (the web client), e.g. `https://app.example.com`
  * @param resendApiKey Resend API key, used only when `provider = "resend"`
  */
final case class EmailConfig(provider: String, from: String, baseUrl: String, resendApiKey: String)

object EmailConfig {
  private val Namespace = "email"

  def fromConfig(config: Config = ConfigFactory.load()): EmailConfig = {
    val email = config.getConfig(Namespace)
    EmailConfig(
      provider = email.getString("provider"),
      from = email.getString("from"),
      baseUrl = email.getString("base-url"),
      resendApiKey = email.getString("resend.api-key")
    )
  }
}
