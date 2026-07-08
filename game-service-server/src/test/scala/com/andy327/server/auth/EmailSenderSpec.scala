package com.andy327.server.auth

import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.server.config.EmailConfig

class EmailSenderSpec extends AnyWordSpec with Matchers {

  "InMemoryEmailSender" should {
    "record password-reset and verification emails with their recipient and token, in send order" in {
      val sender = new InMemoryEmailSender
      sender.sendPasswordReset("alice@example.com", "reset-token").unsafeRunSync()
      sender.sendEmailVerification("bob@example.com", "verify-token").unsafeRunSync()

      sender.sent.unsafeRunSync() shouldBe Vector(
        SentEmail(EmailKind.PasswordReset, "alice@example.com", "reset-token"),
        SentEmail(EmailKind.EmailVerification, "bob@example.com", "verify-token")
      )
    }

    "start with nothing sent" in {
      new InMemoryEmailSender().sent.unsafeRunSync() shouldBe empty
    }
  }

  "NoOpEmailSender" should {
    "accept both email kinds without failing" in {
      NoOpEmailSender.sendPasswordReset("alice@example.com", "t").unsafeRunSync()
      NoOpEmailSender.sendEmailVerification("bob@example.com", "t").unsafeRunSync()
      succeed
    }
  }

  "EmailSender.fromConfig" should {
    "select the Resend sender when the provider is resend" in {
      val config = EmailConfig(provider = "resend", from = "no-reply@x.co", baseUrl = "http://x.co", resendApiKey = "k")
      EmailSender.fromConfig(config) shouldBe a[ResendEmailSender]
    }

    "fall back to the no-op sender for any other provider" in {
      val base = EmailConfig(provider = "noop", from = "no-reply@x.co", baseUrl = "http://x.co", resendApiKey = "")
      EmailSender.fromConfig(base) shouldBe NoOpEmailSender
      EmailSender.fromConfig(base.copy(provider = "something-else")) shouldBe NoOpEmailSender
    }
  }
}
