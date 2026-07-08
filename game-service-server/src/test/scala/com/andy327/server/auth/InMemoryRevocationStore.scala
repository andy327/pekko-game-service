package com.andy327.server.auth

import java.time.{Clock, Instant}
import java.util.UUID

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.Ref

/** A test [[RevocationStore]] with the same semantics as the Redis-backed one, so route and authenticator specs can
  * exercise revocation without Redis. Expiry is driven by an injectable `Clock` so the TTL is deterministic.
  */
class InMemoryRevocationStore(implicit clock: Clock = Clock.systemUTC()) extends RevocationStore {
  import InMemoryRevocationStore.Entry

  private val cutoffs: Ref[IO, Map[UUID, Entry]] = Ref.unsafe(Map.empty)

  override def revokeBefore(accountId: UUID, cutoff: Instant, ttl: FiniteDuration): IO[Unit] =
    IO(Instant.now(clock)).flatMap(now =>
      cutoffs.update(_.updated(accountId, Entry(cutoff, now.plusMillis(ttl.toMillis))))
    )

  override def revokedBefore(accountId: UUID): IO[Option[Instant]] =
    IO(Instant.now(clock)).flatMap { now =>
      cutoffs.get.map(_.get(accountId).filter(_.expiresAt.isAfter(now)).map(_.cutoff))
    }
}

object InMemoryRevocationStore {
  final private case class Entry(cutoff: Instant, expiresAt: Instant)
}
