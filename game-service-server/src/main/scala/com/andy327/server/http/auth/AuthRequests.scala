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
