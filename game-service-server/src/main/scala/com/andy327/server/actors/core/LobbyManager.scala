package com.andy327.server.actors.core

import scala.concurrent.duration._

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{GameId, GameType, PlayerId}
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, Player}

/**
 * A child actor of GameManager that owns all lobby lifecycle state.
 *
 * LobbyManager handles lobby creation, player joins/leaves, and lobby status transitions. For StartGame, it validates
 * the request and delegates actor spawning back to GameManager via the private SpawnGame command, since only a parent
 * actor should spawn children.
 *
 * Active lobbies (WaitingForPlayers, ReadyToStart, InProgress) are kept in an immutable map. When a lobby ends
 * (Completed or Cancelled), it is moved to a Scaffeine TTL cache and evicted after [[recentlyEndedTtl]]. This bounds
 * memory growth while still allowing short-lived status queries for recently-finished games.
 *
 * All commands reply directly to the original replyTo ActorRef[GameManager.GameResponse], so callers see no difference
 * from before the extraction.
 */
object LobbyManager {
  sealed trait Command

  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command
  final case class JoinLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command
  final case class StartGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameManager.GameResponse])
      extends Command
  final case class ListLobbies(replyTo: ActorRef[GameManager.GameResponse]) extends Command
  final case class GetLobbyInfo(gameId: GameId, replyTo: ActorRef[GameManager.GameResponse]) extends Command

  /** Sent by GameManager when a game ends, to update lobby status. */
  final private[core] case class MarkCompleted(gameId: GameId, result: GameLifecycleStatus.GameEnded) extends Command

  val recentlyEndedTtl: FiniteDuration = 1.hour

  def apply(gameManager: ActorRef[GameManager.Command]): Behavior[Command] = {
    val recentlyEnded: Cache[GameId, LobbyMetadata] = Scaffeine()
      .expireAfterWrite(recentlyEndedTtl)
      .build[GameId, LobbyMetadata]()
    running(Map.empty, recentlyEnded, gameManager)
  }

  private def running(
      lobbies: Map[GameId, LobbyMetadata],
      recentlyEnded: Cache[GameId, LobbyMetadata],
      gameManager: ActorRef[GameManager.Command]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case CreateLobby(gameType, host, replyTo) =>
        context.log.info(s"Creating new lobby for game type $gameType with host ${host.name}")
        val lobby = LobbyMetadata.newLobby(gameType, host)
        replyTo ! GameManager.LobbyCreated(lobby.gameId, host)
        running(lobbies + (lobby.gameId -> lobby), recentlyEnded, gameManager)

      case JoinLobby(gameId, player, replyTo) =>
        lobbies.get(gameId) match {
          case Some(metadata) if metadata.status.isJoinable =>
            if (metadata.players.contains(player.id)) {
              replyTo ! GameManager.LobbyErrorResponse(LobbyError.AlreadyInLobby(gameId))
              Behaviors.same
            } else if (metadata.players.size >= metadata.gameType.maxPlayers) {
              replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyFull(gameId))
              Behaviors.same
            } else {
              val updatedPlayers = metadata.players + (player.id -> player)
              val updatedStatus =
                if (updatedPlayers.size >= metadata.gameType.minPlayers) GameLifecycleStatus.ReadyToStart
                else GameLifecycleStatus.WaitingForPlayers
              val updatedMetadata = metadata.copy(players = updatedPlayers, status = updatedStatus)
              replyTo ! GameManager.LobbyJoined(gameId, updatedMetadata, player)
              running(lobbies + (gameId -> updatedMetadata), recentlyEnded, gameManager)
            }

          case Some(_) =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotJoinable(gameId))
            Behaviors.same

          case None =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
            Behaviors.same
        }

      case LeaveLobby(gameId, player, replyTo) =>
        lobbies.get(gameId) match {
          case Some(metadata) =>
            val updatedPlayers = metadata.players - player.id
            val updatedStatus =
              if (updatedPlayers.size >= metadata.gameType.minPlayers) GameLifecycleStatus.ReadyToStart
              else GameLifecycleStatus.WaitingForPlayers
            val newMetadata = metadata.copy(players = updatedPlayers, status = updatedStatus)

            if (!metadata.players.contains(player.id)) {
              context.log.info(s"Player ${player.name} already absent from lobby $gameId")
              replyTo ! GameManager.LobbyLeft(gameId, s"${player.name} already absent from lobby $gameId")
              Behaviors.same
            } else if (player.id == metadata.hostId) {
              context.log.info(s"Host (${player.name}) left lobby $gameId. Cancelling game...")
              val cancelled = newMetadata.copy(status = GameLifecycleStatus.Cancelled)
              recentlyEnded.put(gameId, cancelled)
              replyTo ! GameManager.LobbyLeft(gameId, s"Lobby $gameId ended - host left")
              running(lobbies - gameId, recentlyEnded, gameManager)
            } else {
              context.log.info(s"Player ${player.name} left lobby $gameId")
              replyTo ! GameManager.LobbyLeft(gameId, s"${player.name} left lobby $gameId")
              running(lobbies + (gameId -> newMetadata), recentlyEnded, gameManager)
            }

          case None =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
            Behaviors.same
        }

      case StartGame(gameId, playerId, replyTo) =>
        lobbies.get(gameId) match {
          case Some(metadata) if metadata.hostId == playerId && metadata.status == GameLifecycleStatus.ReadyToStart =>
            context.log.info(s"Lobby $gameId validated for start — delegating game actor spawn to GameManager")
            val updatedMetadata = metadata.copy(status = GameLifecycleStatus.InProgress)
            gameManager ! GameManager.SpawnGame(gameId, metadata.gameType, metadata.players.keySet, replyTo)
            running(lobbies + (gameId -> updatedMetadata), recentlyEnded, gameManager)

          case Some(metadata) if metadata.hostId != playerId =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.NotHostError(gameId))
            Behaviors.same

          case Some(_) =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotReady(gameId))
            Behaviors.same

          case None =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
            Behaviors.same
        }

      case ListLobbies(replyTo) =>
        context.log.info("Listing available lobbies")
        val activeLobbies = lobbies.values.filter(_.status.isJoinable).toList
        replyTo ! GameManager.LobbiesListed(activeLobbies)
        Behaviors.same

      case GetLobbyInfo(gameId, replyTo) =>
        context.log.info(s"Getting lobby metadata for game $gameId")
        lobbies.get(gameId).orElse(recentlyEnded.getIfPresent(gameId)) match {
          case Some(metadata) => replyTo ! GameManager.LobbyInfo(metadata)
          case None           => replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
        }
        Behaviors.same

      case MarkCompleted(gameId, result) =>
        lobbies.get(gameId) match {
          case Some(metadata) =>
            context.log.info(s"Marking lobby $gameId as $result")
            val updated = metadata.copy(status = result)
            recentlyEnded.put(gameId, updated)
            running(lobbies - gameId, recentlyEnded, gameManager)
          case None =>
            context.log.warn(s"Tried to mark unknown lobby $gameId as completed")
            Behaviors.same
        }
    }
  }
}
