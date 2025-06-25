package com.andy327.server.lobby

import java.util.UUID

import com.andy327.model.core.PlayerId

object Player {
  def apply(name: String): Player = Player(UUID.randomUUID(), name)
}

case class Player(id: PlayerId, name: String)

/** Handles PlayerId generation if none is supplied by the user. */
case class IncomingPlayer(id: Option[PlayerId], name: String)

object IncomingPlayerOps {
  implicit class RichIncomingPlayer(val inc: IncomingPlayer) extends AnyVal {
    def toPlayer: Player = Player(inc.id.getOrElse(UUID.randomUUID()), inc.name)
  }
}
