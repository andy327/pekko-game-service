package com.andy327.server.auth

import java.util.UUID

import cats.effect.IO

import com.andy327.persistence.db.Account

/** Why registration can fail. */
sealed trait RegisterError
object RegisterError {

  /** The email is already in use. */
  case object EmailAlreadyRegistered extends RegisterError
}

/** Why login can fail. */
sealed trait LoginError
object LoginError {

  /** Unknown account, wrong password, or an account with no usable password — deliberately not distinguished, so login
    * can't reveal whether an email is registered.
    */
  case object InvalidCredentials extends LoginError
}

/** Why a password change can fail. */
sealed trait ChangePasswordError
object ChangePasswordError {

  /** The supplied current password did not match (or the account has no usable password). */
  case object InvalidCurrentPassword extends ChangePasswordError
}

/** Establishes a real, server-verified identity before a token is issued.
  *
  * This is the seam that replaces self-asserted identity: a client no longer claims who it is, it proves it. The
  * password implementation is the first concrete provider; the trait is shaped so federated providers (e.g. OAuth) can
  * be added without changing token issuance or the account store — they too resolve a credential to an `Account`.
  */
trait IdentityProvider {

  /** Registers a new account from the given credentials, or fails if the email is already registered. */
  def register(username: String, email: String, password: String): IO[Either[RegisterError, Account]]

  /** Verifies credentials and returns the authenticated account, or [[LoginError.InvalidCredentials]]. */
  def authenticate(email: String, password: String): IO[Either[LoginError, Account]]

  /** Changes the password of an already-authenticated account, after verifying its current password. Fails with
    * [[ChangePasswordError.InvalidCurrentPassword]] if that check doesn't pass.
    */
  def changePassword(
      accountId: UUID,
      currentPassword: String,
      newPassword: String
  ): IO[Either[ChangePasswordError, Unit]]

  /** Sets a new password for `accountId` without checking a current one — for a flow that has already authorized the
    * caller out of band (a consumed single-use reset token). A no-op if the account no longer exists.
    */
  def resetPassword(accountId: UUID, newPassword: String): IO[Unit]
}
