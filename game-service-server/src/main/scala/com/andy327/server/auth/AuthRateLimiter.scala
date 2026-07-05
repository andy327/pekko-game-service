package com.andy327.server.auth

import java.time.{Clock, Instant}

import scala.concurrent.duration.{DurationLong, FiniteDuration}

import cats.effect.IO
import cats.effect.kernel.Ref

/** The result of counting one request against a throttle. */
sealed trait RateLimitOutcome
object RateLimitOutcome {

  /** The request is within the limit and may proceed. */
  case object Allowed extends RateLimitOutcome

  /** The request is over the limit; the caller should reject it and retry no sooner than `retryAfter`. */
  final case class Limited(retryAfter: FiniteDuration) extends RateLimitOutcome
}

/** Server-side throttle and lockout state for the auth endpoints, backed by a self-expiring store.
  *
  * Two independent mechanisms share this seam:
  *   - [[throttle]] is a fixed-window request counter (per client IP), the front-line defense against spraying the
  *     register/login endpoints.
  *   - [[recordFailure]]/[[lockStatus]]/[[clearFailures]] implement per-account lockout: repeated failed logins lock an
  *     account (by email) for a cool-off period, so a single account can't be brute-forced across many source IPs.
  *
  * Every key expires on its own (Redis TTLs in production, timestamps in the in-memory implementation), so the store
  * never needs pruning.
  */
trait AuthRateLimiter {

  /** Atomically count one request against a fixed `window` for `key` and report whether it is now within `limit`. The
    * window starts at the first request and is not extended by later ones, so at most `limit` requests are admitted per
    * window. When over the limit, the outcome carries the time remaining until the window resets.
    */
  def throttle(key: String, limit: Int, window: FiniteDuration): IO[RateLimitOutcome]

  /** How long `key` remains locked out, or `None` if it is not currently locked. */
  def lockStatus(key: String): IO[Option[FiniteDuration]]

  /** Record a failed attempt for `key`. Failures are counted within a rolling `window`; once they reach `threshold`
    * the key is locked for `lockout` and the failure count is cleared, so escaping the lock requires the full
    * threshold of failures again.
    */
  def recordFailure(key: String, threshold: Int, window: FiniteDuration, lockout: FiniteDuration): IO[Unit]

  /** Clear the failure count and any active lock for `key`, called after a successful attempt. */
  def clearFailures(key: String): IO[Unit]
}

/** An [[AuthRateLimiter]] that never throttles or locks anything — the default when rate limiting is disabled or no
  * shared store is wired (mirrors the `NoOp` repositories used elsewhere for the same reason).
  */
object NoOpAuthRateLimiter extends AuthRateLimiter {
  override def throttle(key: String, limit: Int, window: FiniteDuration): IO[RateLimitOutcome] =
    IO.pure(RateLimitOutcome.Allowed)
  override def lockStatus(key: String): IO[Option[FiniteDuration]] = IO.pure(None)
  override def recordFailure(key: String, threshold: Int, window: FiniteDuration, lockout: FiniteDuration): IO[Unit] =
    IO.unit
  override def clearFailures(key: String): IO[Unit] = IO.unit
}

/** An in-memory [[AuthRateLimiter]] with the same semantics as the Redis-backed one, used for tests and single-process
  * runs where no Redis is wired. Expiry is driven by an injectable [[Clock]] so lockout windows can be exercised
  * deterministically in tests.
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
