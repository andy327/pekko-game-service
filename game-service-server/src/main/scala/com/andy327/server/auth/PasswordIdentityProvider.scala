package com.andy327.server.auth

import cats.effect.IO

import com.andy327.persistence.db.UserRepository.CreateError
import com.andy327.persistence.db.{Account, UserRepository}

/** [[IdentityProvider]] backed by locally stored Argon2 password hashes.
  *
  * Registration hashes the password and inserts an account; authentication looks the account up by email and verifies
  * the password against the stored hash. On a successful login whose stored hash was produced with weaker parameters
  * than currently configured, the hash is transparently upgraded ([[PasswordHasher.needsRehash]]).
  */
class PasswordIdentityProvider(users: UserRepository, hasher: PasswordHasher) extends IdentityProvider {

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
          case None => IO.pure(Left(LoginError.InvalidCredentials))
        }
      // Unknown email: same error as a bad password, so registration status is not revealed.
      case None => IO.pure(Left(LoginError.InvalidCredentials))
    }

  /** Rehashes and persists the password if its stored hash used weaker parameters than currently configured. */
  private def upgradeIfNeeded(account: Account, phc: String, password: String): IO[Unit] =
    if (hasher.needsRehash(phc)) IO(hasher.hash(password)).flatMap(users.updatePasswordHash(account.id, _))
    else IO.unit
}
