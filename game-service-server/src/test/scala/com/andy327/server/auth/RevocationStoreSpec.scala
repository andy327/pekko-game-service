package com.andy327.server.auth

import java.time.{Clock, Instant, ZoneId, ZoneOffset}
import java.util.UUID

import scala.concurrent.duration._

import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

/** A [[Clock]] whose current instant can be advanced by the test, so the cutoff TTL is deterministic. */
private class MovableClock(start: Instant) extends Clock {
  @volatile private var current: Instant = start
  def advance(by: FiniteDuration): Unit = current = current.plusMillis(by.toMillis)
  override def instant(): Instant = current
  override def getZone: ZoneId = ZoneOffset.UTC
  override def withZone(zone: ZoneId): Clock = this
}

class RevocationStoreSpec extends AnyWordSpec with Matchers {

  "InMemoryRevocationStore" should {
    "return the cutoff last written for an account, and None for an unknown one" in {
      val store = new InMemoryRevocationStore
      val id = UUID.randomUUID()
      val cutoff = Instant.parse("2026-07-05T12:00:00Z")

      store.revokedBefore(id).unsafeRunSync() shouldBe None
      store.revokeBefore(id, cutoff, 1.hour).unsafeRunSync()
      store.revokedBefore(id).unsafeRunSync() shouldBe Some(cutoff)
    }

    "keep accounts independent" in {
      val store = new InMemoryRevocationStore
      val (a, b) = (UUID.randomUUID(), UUID.randomUUID())
      val cutoff = Instant.parse("2026-07-05T12:00:00Z")

      store.revokeBefore(a, cutoff, 1.hour).unsafeRunSync()
      store.revokedBefore(a).unsafeRunSync() shouldBe Some(cutoff)
      store.revokedBefore(b).unsafeRunSync() shouldBe None
    }

    "forget the cutoff once its TTL elapses" in {
      val clock = new MovableClock(Instant.parse("2026-07-05T12:00:00Z"))
      implicit val c: Clock = clock
      val store = new InMemoryRevocationStore
      val id = UUID.randomUUID()

      store.revokeBefore(id, Instant.parse("2026-07-05T12:00:00Z"), 1.hour).unsafeRunSync()
      store.revokedBefore(id).unsafeRunSync() shouldBe defined

      clock.advance(61.minutes)
      store.revokedBefore(id).unsafeRunSync() shouldBe None
    }
  }

  "NoOpRevocationStore" should {
    "never record or report a revocation" in {
      val id = UUID.randomUUID()
      NoOpRevocationStore.revokeBefore(id, Instant.now(), 1.hour).unsafeRunSync()
      NoOpRevocationStore.revokedBefore(id).unsafeRunSync() shouldBe None
    }
  }
}
