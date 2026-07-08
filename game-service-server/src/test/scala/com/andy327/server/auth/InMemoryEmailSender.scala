package com.andy327.server.auth

import cats.effect.IO
import cats.effect.kernel.Ref

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

/** A test [[EmailSender]] that records what it would have sent instead of delivering it, so specs can assert on the
  * emails (and their tokens) a flow produced.
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
