package com.andy327.actor.lobby

import java.time.Instant
import java.util.UUID

import com.andy327.model.core.{GameId, GameType, MatchId, PlayerId}

object LobbyMetadata {

  /** Creates a new lobby for the given game type with the specified host player.
    *
    * This method generates a unique game ID, initializes the player map with the host, stamps the creation time, and
    * sets the lifecycle status to `WaitingForPlayers`.
    *
    * @param gameType the type of game (e.g., TicTacToe)
    * @param host the player who is creating and hosting the lobby
    * @return a new LobbyMetadata instance representing the initialized lobby
    */
  def newLobby(gameType: GameType, host: Player): LobbyMetadata = {
    val gameId = UUID.randomUUID()
    val players = Map(host.id -> host)
    val now = Instant.now()
    LobbyMetadata(gameId, gameType, players, host.id, GameLifecycleStatus.WaitingForPlayers, now, lastActivityAt = now)
  }
}

/** Represents the metadata and current state of a game lobby.
  *
  * This includes the game type, participating players, host identity, game ID, and current lifecycle status.
  *
  * @param gameId unique UUID for the lobby/game
  * @param gameType type of game being played (e.g., TicTacToe)
  * @param players map of player UUIDs to Player objects (includes host and any joined players)
  * @param hostId UUID of the player who created and controls the lobby
  * @param status current lifecycle status of the game (e.g., waiting, in progress, completed)
  * @param createdAt server time the lobby was created; used to order the lobby list newest-first
  * @param currentMatchId the match currently being played in this room, if any; links the room to the live (or
  *                       most-recent) match so an in-progress game can be re-associated with its room after a restart
  * @param matchCount how many matches have been started in this room (incremented on each start/rematch); used to
  *                   rotate the seating so the first-move seat alternates across rematches
  * @param lastActivityAt server time of the last activity in this room (start, leave, chat, match end); used to reap
  *                       idle post-game (Finished) rooms
  */
case class LobbyMetadata(
    gameId: GameId,
    gameType: GameType,
    players: Map[PlayerId, Player], // includes the host
    hostId: PlayerId,
    status: GameLifecycleStatus,
    createdAt: Instant,
    currentMatchId: Option[MatchId] = None,
    matchCount: Int = 0,
    lastActivityAt: Instant = Instant.EPOCH
)
