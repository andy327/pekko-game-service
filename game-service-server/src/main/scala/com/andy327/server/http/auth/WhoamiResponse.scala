package com.andy327.server.http.auth

import java.util.UUID

/** Body of `GET /auth/whoami` — the authenticated caller's identity plus their email-verification state.
  *
  * `verified` is surfaced as a flag rather than enforced: an unverified account can still authenticate and play, so
  * clients read this to decide whether to prompt for verification.
  *
  * @param id the authenticated player's id
  * @param name the player's display name
  * @param verified whether the account's email address has been verified
  */
case class WhoamiResponse(id: UUID, name: String, verified: Boolean)
