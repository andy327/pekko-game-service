package com.andy327.server.lobby

import java.util.UUID

import scala.util.Try

import org.apache.pekko.http.scaladsl.server.{AuthorizationFailedRejection, Rejection}

import com.andy327.model.core.PlayerId
import com.andy327.server.auth.UserContext

object Player {
  def apply(name: String): Player = Player(UUID.randomUUID(), name)

  /**
   * Creates a Player from a decoded JWT payload (`UserContext`).
   *
   * Attempts to parse the `id` string as a UUID. If parsing fails, returns a `AuthorizationFailedRejection` suitable
   * for route rejection.
   *
   * @param user The user context extracted from the JWT token
   * @return Either a valid Player or an authorization rejection
   */
  def fromJWT(user: UserContext): Either[Rejection, Player] =
    Try(UUID.fromString(user.id))
      .map(uuid => Player(uuid, user.name))
      .toEither
      .left.map(_ => AuthorizationFailedRejection)
}

/**
 * Represents a player in the system, with a unique ID and display name.
 *
 * @param id The player's unique identifier (UUID-based PlayerId)
 * @param name The player's display name
 */
case class Player(id: PlayerId, name: String)
