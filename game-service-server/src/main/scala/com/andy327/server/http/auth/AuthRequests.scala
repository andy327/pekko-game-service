package com.andy327.server.http.auth

/** Body of `POST /auth/register` — the credentials for a new account. The account's id is assigned by the server, not
  * supplied by the client.
  *
  * @param username display name
  * @param email login identifier; must be unique
  * @param password plaintext password, hashed server-side before storage
  */
case class RegisterRequest(username: String, email: String, password: String)

/** Body of `POST /auth/token` — the credentials to authenticate an existing account.
  *
  * @param email login identifier
  * @param password plaintext password, verified against the stored hash
  */
case class LoginRequest(email: String, password: String)

/** Body of `POST /auth/password` — change the authenticated account's password. The account is identified by the
  * bearer token, not the body.
  *
  * @param currentPassword the existing password, verified before the change is allowed
  * @param newPassword the replacement password, hashed server-side before storage
  */
case class ChangePasswordRequest(currentPassword: String, newPassword: String)

/** Body of `POST /auth/forgot-password` — request a reset link for an account by email. The endpoint answers the same
  * way whether or not the email is registered, so this never confirms account existence.
  *
  * @param email the address to send a reset link to, if it belongs to an account
  */
case class ForgotPasswordRequest(email: String)

/** Body of `POST /auth/reset-password` — set a new password using a token from a reset email. The account is identified
  * by the token, not the body.
  *
  * @param token the single-use reset token delivered by email
  * @param newPassword the replacement password, hashed server-side before storage
  */
case class ResetPasswordRequest(token: String, newPassword: String)
