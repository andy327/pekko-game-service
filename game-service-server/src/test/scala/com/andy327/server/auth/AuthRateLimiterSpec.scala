package com.andy327.server.auth

import java.time.{Clock, Instant, ZoneId, ZoneOffset}

import scala.concurrent.duration._

import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** A [[Clock]] whose current instant can be advanced by the test, so window/lockout expiry is deterministic. */
private class MutableClock(start: Instant) extends Clock {
  @volatile private var current: Instant = start
  def advance(by: FiniteDuration): Unit = current = current.plusMillis(by.toMillis)
  override def instant(): Instant = current
  override def getZone: ZoneId = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this
}

class AuthRateLimiterSpec extends AnyWordSpec with Matchers {

  private def newLimiter(clock: MutableClock): InMemoryAuthRateLimiter = {
    implicit val c: Clock = clock
    new InMemoryAuthRateLimiter
  }

  "InMemoryAuthRateLimiter.throttle" should {
    "admit requests up to the limit and throttle the next one" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)

      limiter.throttle("ip", limit = 2, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed
      limiter.throttle("ip", limit = 2, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed

      limiter.throttle("ip", limit = 2, window = 1.minute).unsafeRunSync() match {
        case RateLimitOutcome.Limited(retryAfter) => retryAfter should ((be <= 1.minute).and(be > 0.seconds))
        case other                                => fail(s"expected Limited, got $other")
      }
    }

    "reset the window once it elapses" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)

      limiter.throttle("ip", limit = 1, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed
      limiter.throttle("ip", limit = 1, window = 1.minute).unsafeRunSync() shouldBe a[RateLimitOutcome.Limited]

      clock.advance(61.seconds)
      limiter.throttle("ip", limit = 1, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed
    }

    "count keys independently" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)

      limiter.throttle("a", limit = 1, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed
      limiter.throttle("b", limit = 1, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed
      limiter.throttle("a", limit = 1, window = 1.minute).unsafeRunSync() shouldBe a[RateLimitOutcome.Limited]
    }
  }

  "InMemoryAuthRateLimiter lockout" should {
    "lock a key only once the failure threshold is reached" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)
      def recordFail(): Unit = limiter.recordFailure("acct", threshold = 3, window = 15.minutes, lockout = 15.minutes)
        .unsafeRunSync()

      recordFail()
      recordFail()
      limiter.lockStatus("acct").unsafeRunSync() shouldBe None

      recordFail()
      limiter.lockStatus("acct").unsafeRunSync() match {
        case Some(remaining) => remaining should ((be <= 15.minutes).and(be > 0.seconds))
        case None            => fail("expected the account to be locked")
      }
    }

    "release the lock after the lockout elapses" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)
      (1 to 3).foreach(_ =>
        limiter.recordFailure("acct", threshold = 3, window = 15.minutes, lockout = 15.minutes).unsafeRunSync()
      )
      limiter.lockStatus("acct").unsafeRunSync() shouldBe defined

      clock.advance(16.minutes)
      limiter.lockStatus("acct").unsafeRunSync() shouldBe None
    }

    "not count failures that fall outside the rolling window toward the threshold" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)
      def recordFail(): Unit = limiter.recordFailure("acct", threshold = 2, window = 5.minutes, lockout = 15.minutes)
        .unsafeRunSync()

      recordFail()
      clock.advance(6.minutes) // first failure's window has elapsed
      recordFail()
      limiter.lockStatus("acct").unsafeRunSync() shouldBe None
    }

    "clear the failure count and any lock on success" in {
      val clock = new MutableClock(Instant.parse("2026-07-05T00:00:00Z"))
      val limiter = newLimiter(clock)
      (1 to 3).foreach(_ =>
        limiter.recordFailure("acct", threshold = 3, window = 15.minutes, lockout = 15.minutes).unsafeRunSync()
      )
      limiter.lockStatus("acct").unsafeRunSync() shouldBe defined

      limiter.clearFailures("acct").unsafeRunSync()
      limiter.lockStatus("acct").unsafeRunSync() shouldBe None
    }
  }

  "NoOpAuthRateLimiter" should {
    "never throttle or lock" in {
      NoOpAuthRateLimiter.throttle("ip", limit = 0, window = 1.minute).unsafeRunSync() shouldBe RateLimitOutcome.Allowed
      NoOpAuthRateLimiter.recordFailure("acct", threshold = 1, window = 1.minute, lockout = 1.minute).unsafeRunSync()
      NoOpAuthRateLimiter.clearFailures("acct").unsafeRunSync()
      NoOpAuthRateLimiter.lockStatus("acct").unsafeRunSync() shouldBe None
    }
  }
}
