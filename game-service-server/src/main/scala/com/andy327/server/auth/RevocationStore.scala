package com.andy327.server.auth

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO

/** Records per-account token-revocation cutoffs, the state that lets stateless JWTs be invalidated before they expire.
  *
  * JWTs carry an `iat` (issued-at) and are otherwise self-contained, so logout and password changes can't reach the
  * tokens already handed out. Instead, an account records a *cutoff*: every token it issued at or before that instant
  * is considered revoked. Enforcement compares a presented token's `iat` against the account's cutoff.
  *
  * The cutoff is remembered only for the token lifetime (`ttl`): once that elapses, every token predating the cutoff
  * has expired on its own, so the record can disappear. Backing stores implement this with a native TTL, so the store
  * self-prunes and never grows unbounded.
  */
trait RevocationStore {

  /** Revoke every token for `accountId` issued at or before `cutoff`, remembering it for `ttl`. */
  def revokeBefore(accountId: UUID, cutoff: Instant, ttl: FiniteDuration): IO[Unit]

  /** The current revocation cutoff for `accountId`, or `None` if nothing is revoked (or the record has expired). */
  def revokedBefore(accountId: UUID): IO[Option[Instant]]
}

/** A [[RevocationStore]] that never revokes anything — the default when no shared store is wired (tests, local runs),
  * mirroring the other `NoOp` collaborators. With this store every validly-signed, unexpired token is accepted.
  */
object NoOpRevocationStore extends RevocationStore {
  override def revokeBefore(accountId: UUID, cutoff: Instant, ttl: FiniteDuration): IO[Unit] = IO.unit
  override def revokedBefore(accountId: UUID): IO[Option[Instant]] = IO.pure(None)
}
