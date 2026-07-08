package com.andy327.server.auth

import cats.effect.IO
import cats.effect.kernel.Ref

import com.andy327.server.config.EmailConfig

/** Which transactional email a message is, used by the in-memory sender so tests can assert what was sent. */
sealed trait EmailKind
object EmailKind {
  case object PasswordReset extends EmailKind
  case object EmailVerification extends EmailKind
}

/** A record of an email the [[InMemoryEmailSender]] captured instead of delivering, so tests can assert what a flow
  * would have sent. It is produced by the sender, not passed to it — the sender's methods take the recipient and token.
  *
  * @param kind which flow the email belongs to
  * @param to recipient address
  * @param token the raw single-use token the email would carry (embedded in its link)
  */
final case class SentEmail(kind: EmailKind, to: String, token: String)

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

/** An [[EmailSender]] that records what it would have sent instead of delivering it, so tests can assert on the emails
  * (and their tokens) a flow produced. Also usable for local runs where seeing the token in a test hook is enough.
  */
class InMemoryEmailSender extends EmailSender {
  private val sentRef: Ref[IO, Vector[SentEmail]] = Ref.unsafe(Vector.empty)

  /** Every email recorded so far, in send order. */
  def sent: IO[Vector[SentEmail]] = sentRef.get

  override def sendPasswordReset(to: String, token: String): IO[Unit] =
    sentRef.update(_ :+ SentEmail(EmailKind.PasswordReset, to, token))

  override def sendEmailVerification(to: String, token: String): IO[Unit] =
    sentRef.update(_ :+ SentEmail(EmailKind.EmailVerification, to, token))
}
