package com.andy327.server.lobby

import java.util.UUID

object Player {
  def apply(name: String): Player = Player(UUID.randomUUID(), name)
}

case class Player(id: UUID, name: String)
