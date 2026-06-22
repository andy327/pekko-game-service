package com.andy327.server.auth

import cats.effect.IO

import com.andy327.persistence.db.Account

/** Why authentication can fail. */
sealed trait AuthError
object AuthError {

  /** The email is already in use. */
  case object EmailAlreadyRegistered extends AuthError

  /** Unknown account, wrong password, or an account with no usable password — deliberately not distinguished, so login
    * can't reveal whether an email is registered.
    */
  case object InvalidCredentials extends AuthError
}

/** Establishes a real, server-verified identity before a token is issued.
  *
  * This is the seam that replaces self-asserted identity: a client no longer claims who it is, it proves it. The
  * password implementation is the first concrete provider; the trait is shaped so federated providers (e.g. OAuth) can
  * be added without changing token issuance or the account store — they too resolve a credential to an `Account`.
  */
trait IdentityProvider {

  /** Registers a new account from the given credentials, or fails if the email is already registered. */
  def register(username: String, email: String, password: String): IO[Either[AuthError, Account]]

  /** Verifies credentials and returns the authenticated account, or [[AuthError.InvalidCredentials]]. */
  def authenticate(email: String, password: String): IO[Either[AuthError, Account]]
}
