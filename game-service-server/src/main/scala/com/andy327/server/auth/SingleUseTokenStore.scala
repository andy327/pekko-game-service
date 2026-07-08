package com.andy327.server.auth

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.time.{Clock, Instant}
import java.util.{Base64, UUID}

import scala.concurrent.duration.FiniteDuration

import cats.effect.IO
import cats.effect.kernel.Ref

/** What an issued token authorizes, and the keyspace it lives in. A token is only ever accepted for the exact purpose
  * it was minted for, so a reset token can't be replayed as a verification token or vice versa.
  */
sealed trait TokenPurpose {

  /** Short, stable keyspace segment for this purpose (part of the store key). */
  def key: String
}
object TokenPurpose {

  /** A password-reset token (consumed by `POST /auth/reset-password`). */
  case object PasswordReset extends TokenPurpose { val key = "reset" }

  /** An email-address-verification token (consumed by `POST /auth/verify`). */
  case object EmailVerification extends TokenPurpose { val key = "verify" }
}

/** A single-use, TTL'd store mapping opaque tokens to the account they authorize, keyed by [[TokenPurpose]].
  *
  * The out-of-band-token flow: mint a high-entropy token, hand the raw value to the user (over email), and later
  * exchange it once for the account id. Only a hash of the token is ever stored, so a leak of the store never yields a
  * usable token. Consumption is atomic and single-use — a token disappears the moment it is redeemed, so it can't be
  * replayed.
  *
  * Each entry expires on its own after its TTL (Redis TTLs in production, timestamps in the in-memory implementation),
  * so the store self-prunes and never grows unbounded.
  */
trait SingleUseTokenStore {

  /** Mint a fresh opaque token for `accountId` under `purpose`, valid for `ttl`, and return the raw token. The store
    * keeps only a hash of it, never the raw value.
    */
  def issue(purpose: TokenPurpose, accountId: UUID, ttl: FiniteDuration): IO[String]

  /** Atomically redeem `rawToken` for `purpose`, returning the account id it authorizes and removing it so it can't be
    * reused. `None` if the token is unknown, already used, expired, or was minted for a different purpose.
    */
  def consume(purpose: TokenPurpose, rawToken: String): IO[Option[UUID]]
}

object SingleUseTokenStore {
  private val random = new SecureRandom()

  /** Bytes of entropy per token; 32 bytes (256 bits) is well beyond guessable and keeps the encoded token compact. */
  private val TokenBytes = 32

  /** Generates a URL-safe, unpadded Base64 token from `TokenBytes` of cryptographically strong randomness. */
  def generateToken(): String = {
    val bytes = new Array[Byte](TokenBytes)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  /** SHA-256 hex digest of a raw token — what the store persists, so the raw token is never at rest. */
  def hash(rawToken: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(rawToken.getBytes(StandardCharsets.UTF_8))
      .map("%02x".format(_))
      .mkString

  /** The store key for a token: purpose-namespaced hash, so purposes never collide and the raw token isn't stored. */
  private[auth] def storeKey(purpose: TokenPurpose, rawToken: String): String = s"${purpose.key}:${hash(rawToken)}"
}

/** A [[SingleUseTokenStore]] that issues tokens which can never be redeemed — the default when no shared store is wired
  * or email is disabled. `issue` still returns a well-formed token so callers need no special-casing, but `consume`
  * always reports `None`, so reset/verify flows are inert.
  */
object NoOpSingleUseTokenStore extends SingleUseTokenStore {
  override def issue(purpose: TokenPurpose, accountId: UUID, ttl: FiniteDuration): IO[String] =
    IO(SingleUseTokenStore.generateToken())
  override def consume(purpose: TokenPurpose, rawToken: String): IO[Option[UUID]] = IO.pure(None)
}

/** An in-memory [[SingleUseTokenStore]] with the same semantics as the Redis-backed one, for tests and single-process
  * runs. Only token hashes are held, and expiry is driven by an injectable `Clock` so the TTL can be exercised
  * deterministically.
  */
class InMemorySingleUseTokenStore(implicit clock: Clock = Clock.systemUTC()) extends SingleUseTokenStore {
  import InMemorySingleUseTokenStore.Entry

  private val entries: Ref[IO, Map[String, Entry]] = Ref.unsafe(Map.empty)

  override def issue(purpose: TokenPurpose, accountId: UUID, ttl: FiniteDuration): IO[String] =
    for {
      raw <- IO(SingleUseTokenStore.generateToken())
      now <- IO(Instant.now(clock))
      entry = Entry(accountId, now.plusMillis(ttl.toMillis))
      _ <- entries.update(_.updated(SingleUseTokenStore.storeKey(purpose, raw), entry))
    } yield raw

  override def consume(purpose: TokenPurpose, rawToken: String): IO[Option[UUID]] =
    IO(Instant.now(clock)).flatMap { now =>
      val key = SingleUseTokenStore.storeKey(purpose, rawToken)
      entries.modify { current =>
        current.get(key) match {
          // Present and live: redeem it and remove it so it can't be reused.
          case Some(entry) if entry.expiresAt.isAfter(now) => (current - key, Some(entry.accountId))
          // Present but expired: drop it and reject, matching the Redis store where the key would already be gone.
          case Some(_) => (current - key, None)
          case None    => (current, None)
        }
      }
    }
}

object InMemorySingleUseTokenStore {
  final private case class Entry(accountId: UUID, expiresAt: Instant)
}
