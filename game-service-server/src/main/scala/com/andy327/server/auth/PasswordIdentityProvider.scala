package com.andy327.server.auth

import java.util.UUID

import cats.effect.IO

import com.andy327.persistence.db.UserRepository.CreateError
import com.andy327.persistence.db.{Account, UserRepository}

/** [[IdentityProvider]] backed by locally stored Argon2 password hashes.
  *
  * Registration hashes the password and inserts an account; authentication looks the account up by email and verifies
  * the password against the stored hash. On a successful login whose stored hash was produced with weaker parameters
  * than currently configured, the hash is transparently upgraded ([[PasswordHasher.needsRehash]]).
  *
  * When no account (or no usable password) is found, the password is still verified against a throwaway hash so the
  * request spends comparable time to a real verification — otherwise the fast no-account path would leak, via response
  * time, whether an email is registered.
  */
class PasswordIdentityProvider(users: UserRepository, hasher: PasswordHasher) extends IdentityProvider {

  /** A hash no real password verifies against, used only to equalize timing on the rejection paths. */
  private val decoyHash: String = hasher.hash("password-identity-provider-decoy")

  override def register(username: String, email: String, password: String): IO[Either[RegisterError, Account]] =
    for {
      hash <- IO(hasher.hash(password))
      created <- users.create(username, email, Some(hash))
    } yield created.left.map { case CreateError.EmailAlreadyExists => RegisterError.EmailAlreadyRegistered }

  override def authenticate(email: String, password: String): IO[Either[LoginError, Account]] =
    users.findByEmail(email).flatMap {
      case Some(account) =>
        account.passwordHash match {
          case Some(phc) =>
            IO(hasher.verify(password, phc)).flatMap {
              case true  => upgradeIfNeeded(account, phc, password).as(Right(account))
              case false => IO.pure(Left(LoginError.InvalidCredentials))
            }
          // An account with no local password (e.g. a future federated identity) cannot be logged in with one.
          case None => rejectWithEqualizedTiming(password)
        }
      // Unknown email: same error as a bad password, so registration status is not revealed.
      case None => rejectWithEqualizedTiming(password)
    }

  override def changePassword(
      accountId: UUID,
      currentPassword: String,
      newPassword: String
  ): IO[Either[ChangePasswordError, Unit]] =
    users.findById(accountId).flatMap {
      case Some(account) =>
        account.passwordHash match {
          case Some(phc) =>
            IO(hasher.verify(currentPassword, phc)).flatMap {
              case true  => IO(hasher.hash(newPassword)).flatMap(users.updatePasswordHash(accountId, _)).as(Right(()))
              case false => IO.pure(Left(ChangePasswordError.InvalidCurrentPassword))
            }
          case None => IO.pure(Left(ChangePasswordError.InvalidCurrentPassword))
        }
      // The token referenced an account that no longer exists; treat as a failed current-password check.
      case None => IO.pure(Left(ChangePasswordError.InvalidCurrentPassword))
    }

  /** Rejects with [[LoginError.InvalidCredentials]] after a decoy verification, so the no-account and wrong-password
    * paths take comparable time and don't reveal whether an email is registered.
    */
  private def rejectWithEqualizedTiming(password: String): IO[Either[LoginError, Account]] =
    IO(hasher.verify(password, decoyHash)).as(Left(LoginError.InvalidCredentials))

  /** Rehashes and persists the password if its stored hash used weaker parameters than currently configured. */
  private def upgradeIfNeeded(account: Account, phc: String, password: String): IO[Unit] =
    if (hasher.needsRehash(phc)) IO(hasher.hash(password)).flatMap(users.updatePasswordHash(account.id, _))
    else IO.unit
}
