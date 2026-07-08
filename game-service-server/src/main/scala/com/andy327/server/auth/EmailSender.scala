package com.andy327.server.auth

import cats.effect.IO

import com.andy327.server.config.EmailConfig

/** Sends the transactional emails the credential lifecycle depends on, as an injectable seam.
  *
  * A provider-backed implementation delivers for real; an in-memory recorder and a no-op stand in for tests and local
  * runs, so the surrounding flows can be exercised without sending mail. Implementations receive only the recipient and
  * the raw token — rendering the link and message body is the implementation's concern, so the token never leaks into
  * logs or call sites that don't need it.
  */
trait EmailSender {

  /** Send a password-reset email carrying `token` to `to`. */
  def sendPasswordReset(to: String, token: String): IO[Unit]

  /** Send an email-address-verification email carrying `token` to `to`. */
  def sendEmailVerification(to: String, token: String): IO[Unit]
}

object EmailSender {

  /** Selects an [[EmailSender]] from config: the Resend provider when `email.provider = "resend"`, otherwise the no-op
    * sender that drops mail (local runs and CI).
    */
  def fromConfig(config: EmailConfig): EmailSender =
    config.provider match {
      case "resend" => new ResendEmailSender(config.resendApiKey, config.from, config.baseUrl)
      case _        => NoOpEmailSender
    }
}

/** An [[EmailSender]] that delivers nothing — the default when no provider is configured (local runs, or CI without
  * real credentials). Callers still mint and store tokens; they just don't go anywhere, so the flow is inert rather
  * than failing.
  */
object NoOpEmailSender extends EmailSender {
  override def sendPasswordReset(to: String, token: String): IO[Unit] = IO.unit
  override def sendEmailVerification(to: String, token: String): IO[Unit] = IO.unit
}
