package com.andy327.actor.lobby

import java.util.UUID

import com.andy327.model.core.PlayerId

object Player {
  def apply(name: String): Player = Player(UUID.randomUUID(), name)
}

/** Represents a player in the system, with a unique ID and display name.
  *
  * @param id The player's unique identifier (UUID-based PlayerId)
  * @param name The player's display name
  */
case class Player(id: PlayerId, name: String)
