package com.andy327.server.lobby

import java.util.UUID

import com.andy327.model.core.GameType

object LobbyMetadata {

  /**
   * Creates a new lobby for the given game type with the specified host player.
   *
   * This method generates a unique game ID, initializes the player map with the host, and sets the lifecycle status to
   * `WaitingForPlayers`.
   *
   * @param gameType the type of game (e.g., TicTacToe)
   * @param host the player who is creating and hosting the lobby
   * @return a new LobbyMetadata instance representing the initialized lobby
   */
  def newLobby(gameType: GameType, host: Player): LobbyMetadata = {
    val gameId = UUID.randomUUID()
    val players = Map(host.id -> host)
    LobbyMetadata(gameId, gameType, players, host.id, GameLifecycleStatus.WaitingForPlayers)
  }
}

/**
 * Represents the metadata and current state of a game lobby.
 *
 * This includes the game type, participating players, host identity, game ID, and current lifecycle status.
 *
 * @param gameId unique identifier for the lobby/game
 * @param gameType type of game being played (e.g., TicTacToe)
 * @param players map of player UUIDs to Player objects (includes host and any joined players)
 * @param hostId UUID of the player who created and controls the lobby
 * @param status current lifecycle status of the game (e.g., waiting, in progress, completed)
 */
case class LobbyMetadata(
    gameId: UUID,
    gameType: GameType,
    players: Map[UUID, Player], // includes the host
    hostId: UUID,
    status: GameLifecycleStatus
)
