package com.andy327.server.auth

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.UUID

import scala.concurrent.duration._

import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** A [[Clock]] whose current instant can be advanced by the test, so token TTLs are deterministic. */
private class AdjustableClock(start: Instant) extends Clock {
  @volatile private var current: Instant = start
  def advance(by: FiniteDuration): Unit = current = current.plusMillis(by.toMillis)
  override def instant(): Instant = current
  override def getZone: ZoneId = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this
}

class SingleUseTokenStoreSpec extends AnyWordSpec with Matchers {

  "InMemorySingleUseTokenStore" should {
    "redeem a freshly issued token for its account exactly once" in {
      val store = new InMemorySingleUseTokenStore
      val account = UUID.randomUUID()

      val token = store.issue(TokenPurpose.PasswordReset, account, 1.hour).unsafeRunSync()
      store.consume(TokenPurpose.PasswordReset, token).unsafeRunSync() shouldBe Some(account)
      // Single-use: a second redemption of the same token finds nothing.
      store.consume(TokenPurpose.PasswordReset, token).unsafeRunSync() shouldBe None
    }

    "reject an unknown token" in {
      val store = new InMemorySingleUseTokenStore
      store.consume(TokenPurpose.PasswordReset, "not-a-real-token").unsafeRunSync() shouldBe None
    }

    "reject a token presented under a different purpose than it was issued for" in {
      val store = new InMemorySingleUseTokenStore
      val account = UUID.randomUUID()

      val token = store.issue(TokenPurpose.PasswordReset, account, 1.hour).unsafeRunSync()
      store.consume(TokenPurpose.EmailVerification, token).unsafeRunSync() shouldBe None
      // The token is untouched by the wrong-purpose attempt and still redeemable for its real purpose.
      store.consume(TokenPurpose.PasswordReset, token).unsafeRunSync() shouldBe Some(account)
    }

    "reject a token once its TTL has elapsed" in {
      val clock = new AdjustableClock(Instant.parse("2026-07-07T12:00:00Z"))
      implicit val c: Clock = clock
      val store = new InMemorySingleUseTokenStore
      val account = UUID.randomUUID()

      val token = store.issue(TokenPurpose.EmailVerification, account, 1.hour).unsafeRunSync()
      clock.advance(61.minutes)
      store.consume(TokenPurpose.EmailVerification, token).unsafeRunSync() shouldBe None
    }

    "keep tokens for different accounts and purposes independent" in {
      val store = new InMemorySingleUseTokenStore
      val (a, b) = (UUID.randomUUID(), UUID.randomUUID())

      val resetA = store.issue(TokenPurpose.PasswordReset, a, 1.hour).unsafeRunSync()
      val verifyB = store.issue(TokenPurpose.EmailVerification, b, 1.hour).unsafeRunSync()

      store.consume(TokenPurpose.PasswordReset, resetA).unsafeRunSync() shouldBe Some(a)
      store.consume(TokenPurpose.EmailVerification, verifyB).unsafeRunSync() shouldBe Some(b)
    }

    "issue distinct high-entropy tokens" in {
      val store = new InMemorySingleUseTokenStore
      val account = UUID.randomUUID()
      val tokens = List.fill(50)(store.issue(TokenPurpose.PasswordReset, account, 1.hour).unsafeRunSync())
      tokens.distinct.size shouldBe tokens.size
      tokens.foreach(_.length should be > 20)
    }
  }

  "NoOpSingleUseTokenStore" should {
    "issue a well-formed token that can never be redeemed" in {
      val account = UUID.randomUUID()
      val token = NoOpSingleUseTokenStore.issue(TokenPurpose.PasswordReset, account, 1.hour).unsafeRunSync()
      token should not be empty
      NoOpSingleUseTokenStore.consume(TokenPurpose.PasswordReset, token).unsafeRunSync() shouldBe None
    }
  }
}
