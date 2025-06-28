package com.andy327.server.auth

import io.circe.generic.semiauto._
import io.circe.{Decoder, Encoder}

/**
 * Provides Circe encoders/decoders for serializing and deserializing UserContext instances to and from JSON, for
 * encoding into and decoding from JWT payloads.
 */
object UserContext {
  implicit val decoder: Decoder[UserContext] = deriveDecoder
  implicit val encoder: Encoder[UserContext] = deriveEncoder
}

/**
 * Represents the authenticated user context extracted from a JWT token.
 *
 * @param id The user's unique identifier (as a string representing a UUID)
 * @param name The display name or username of the player
 */
case class UserContext(id: String, name: String)
