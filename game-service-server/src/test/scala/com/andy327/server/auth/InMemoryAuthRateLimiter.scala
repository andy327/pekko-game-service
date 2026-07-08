package com.andy327.server.auth

import java.time.{Clock, Instant}

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import cats.effect.IO
import cats.effect.kernel.Ref

/** A test [[AuthRateLimiter]] with the same semantics as the Redis-backed one, so route specs can exercise throttling
  * and lockout without Redis. Expiry is driven by an injectable `Clock` so windows are deterministic.
  */
class InMemoryAuthRateLimiter(implicit clock: Clock = Clock.systemUTC()) extends AuthRateLimiter {
  import InMemoryAuthRateLimiter._

  private val state: Ref[IO, State] = Ref.unsafe(State(Map.empty, Map.empty))

  override def throttle(key: String, limit: Int, window: FiniteDuration): IO[RateLimitOutcome] =
    IO(Instant.now(clock)).flatMap { now =>
      state.modify { s =>
        val counter = s.counters.get(key).filter(_.expiresAt.isAfter(now)) match {
          case Some(w) => w.copy(count = w.count + 1)
          case None    => Counter(1, now.plusMillis(window.toMillis))
        }
        val outcome =
          if (counter.count > limit) RateLimitOutcome.Limited(remaining(now, counter.expiresAt))
          else RateLimitOutcome.Allowed
        (s.copy(counters = s.counters.updated(key, counter)), outcome)
      }
    }

  override def lockStatus(key: String): IO[Option[FiniteDuration]] =
    IO(Instant.now(clock)).flatMap(now => state.get.map(_.locks.get(key).filter(_.isAfter(now)).map(remaining(now, _))))

  override def recordFailure(key: String, threshold: Int, window: FiniteDuration, lockout: FiniteDuration): IO[Unit] =
    IO(Instant.now(clock)).flatMap { now =>
      state.update { s =>
        val counter = s.counters.get(key).filter(_.expiresAt.isAfter(now)) match {
          case Some(w) => w.copy(count = w.count + 1)
          case None    => Counter(1, now.plusMillis(window.toMillis))
        }
        if (counter.count >= threshold)
          s.copy(counters = s.counters - key, locks = s.locks.updated(key, now.plusMillis(lockout.toMillis)))
        else
          s.copy(counters = s.counters.updated(key, counter))
      }
    }

  override def clearFailures(key: String): IO[Unit] =
    state.update(s => s.copy(counters = s.counters - key, locks = s.locks - key))
}

object InMemoryAuthRateLimiter {
  final private case class Counter(count: Long, expiresAt: Instant)
  final private case class State(counters: Map[String, Counter], locks: Map[String, Instant])

  /** The non-negative duration from `now` until `expiresAt`. */
  private def remaining(now: Instant, expiresAt: Instant): FiniteDuration =
    math.max(0L, expiresAt.toEpochMilli - now.toEpochMilli).millis
}
