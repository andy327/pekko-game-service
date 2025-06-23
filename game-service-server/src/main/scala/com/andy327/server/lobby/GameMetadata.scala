package com.andy327.server.lobby

import java.util.UUID

import com.andy327.model.core.GameType

object GameMetadata {
  def newLobby(gameType: GameType, host: Player): GameMetadata = {
    val gameId = UUID.randomUUID().toString
    val players = Map(host.id -> host)
    GameMetadata(gameId, gameType, players, host.id, GameLifecycleStatus.WaitingForPlayers)
  }
}

case class GameMetadata(
    gameId: String,
    gameType: GameType,
    players: Map[UUID, Player], // includes the host
    hostId: UUID,
    status: GameLifecycleStatus
)
