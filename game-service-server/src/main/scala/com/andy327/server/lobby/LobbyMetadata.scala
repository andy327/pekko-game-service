package com.andy327.server.lobby

import java.util.UUID

import com.andy327.model.core.GameType

object LobbyMetadata {
  def newLobby(gameType: GameType, host: Player): LobbyMetadata = {
    val gameId = UUID.randomUUID().toString
    val players = Map(host.id -> host)
    LobbyMetadata(gameId, gameType, players, host.id, GameLifecycleStatus.WaitingForPlayers)
  }
}

case class LobbyMetadata(
    gameId: String,
    gameType: GameType,
    players: Map[UUID, Player], // includes the host
    hostId: UUID,
    status: GameLifecycleStatus
)
