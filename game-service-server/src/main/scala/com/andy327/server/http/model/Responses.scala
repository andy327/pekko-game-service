package com.andy327.server.http.model

/** The single error envelope for every non-2xx HTTP response across the API. Whatever the failure — a validation
  * rejection, an auth failure, a not-found, a rate-limit, or an internal error translated from an actor reply — the
  * body is `{ "error": "<human-readable message>" }`, so clients read one shape regardless of which route produced it.
  *
  * @param error a human-readable description of what went wrong; not machine-parseable and safe to surface to a user
  */
case class ErrorResponse(error: String)

/** An advisory 2xx body carrying a single human-readable message, used where a success has nothing to return but a
  * note to the caller — e.g. the deliberately non-committal 202 from the forgot-password and resend-verification
  * endpoints. The body is `{ "message": "<text>" }`.
  *
  * @param message a human-readable note about the outcome
  */
case class MessageResponse(message: String)
