package com.andy327.server.actors.core

import scala.concurrent.duration._

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{GameId, GameType, PlayerId}
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, LobbyRepository, Player}

/** A child actor of GameManager that owns all lobby lifecycle state.
  *
  * LobbyManager handles lobby creation, player joins/leaves, and lobby status transitions. For StartGame, it validates
  * the request and delegates actor spawning back to GameManager via the private SpawnGame command, since only a parent
  * actor should spawn children.
  *
  * Active lobbies (WaitingForPlayers, ReadyToStart, InProgress) are kept in an immutable map. When a lobby ends
  * (Completed or Cancelled), it is moved to a Scaffeine TTL cache and evicted after [[recentlyEndedTtl]]. This bounds
  * memory growth while still allowing short-lived status queries for recently-finished games.
  *
  * PlayerActors subscribe to a lobby via SubscribeToLobby and receive LobbyUpdated events on each state change. When a
  * game starts, the subscriber set is handed off to the new GameActor via SpawnGame.
  *
  * All commands reply directly to the original replyTo ActorRef[GameManager.GameResponse], so callers see no difference
  * from before the extraction.
  *
  * On startup, [[GameManager]] sends a `RestoreLobbies` message with lobbies loaded from Redis; the actor replaces
  * its empty map with the restored state before processing any other commands.
  *
  * Lobby persistence is fire-and-forget: [[lobby.LobbyRepository]] writes are dispatched asynchronously after each
  * state-changing command. A Redis write failure is logged as a warning but does not affect the actor's behavior.
  *
  * Actor relationships:
  *   - Parent: [[GameManager]]
  *   - Receives from: [[GameManager]] (all lobby `Command` messages)
  *   - Sends to: [[GameManager]] (`SpawnGame` to trigger game actor creation), [[PlayerActor]] (fan-out `LobbyUpdated`
  *     and `GameEnded` events via `SendEvent`)
  */
object LobbyManager {
  sealed trait Command

  // --- Commands received from GameManager (all handled locally by LobbyManager) ---

  /** Create a new lobby for `gameType` hosted by `host`; replies with [[GameManager.LobbyCreated]]. */
  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command

  /** Add `player` to an existing joinable lobby; replies with [[GameManager.LobbyJoined]] or an error. */
  final case class JoinLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command

  /** Remove `player` from a lobby; cancels the lobby if the departing player is the host. */
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command

  /** Validate the start request and, if valid, ask GameManager to spawn the game actor via a `SpawnGame` message. */
  final case class StartGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameManager.GameResponse])
      extends Command

  /** Return a paginated, optionally filtered list of joinable lobbies; replies with [[LobbiesListed]]. */
  final case class ListLobbies(
      gameType: Option[GameType],
      page: Int,
      limit: Int,
      replyTo: ActorRef[LobbyManager.LobbiesListed]
  ) extends Command

  /** Return full metadata for one lobby (active or recently ended); replies with [[GameManager.LobbyInfo]]. */
  final case class GetLobbyInfo(gameId: GameId, replyTo: ActorRef[GameManager.GameResponse]) extends Command

  /** Register `playerRef` to receive [[PlayerEvent.LobbyUpdated]] push events for `gameId`.
    *
    * Rejects with [[GameManager.LobbyErrorResponse]](`LobbyError.GameAlreadyStarted`) if the game is already
    * in progress; the caller should use the game subscribe endpoint instead. Pass `context.system.ignoreRef` for
    * `replyTo` on auto-subscribe paths where no acknowledgment is needed.
    */
  final case class SubscribeToLobby(
      gameId: GameId,
      playerId: PlayerId,
      playerRef: ActorRef[PlayerActor.Command],
      replyTo: ActorRef[GameManager.GameResponse]
  ) extends Command

  // --- Internal commands (sent by GameManager, not reachable from HTTP) ---

  /** Move the lobby to the recently-ended cache and fan-out a [[PlayerEvent.GameEnded]] to subscribers. */
  final private[core] case class MarkCompleted(gameId: GameId, result: GameLifecycleStatus.GameEnded) extends Command

  /** Replace the in-memory lobby map with lobbies restored from Redis at startup. */
  final private[core] case class RestoreLobbies(lobbies: List[LobbyMetadata]) extends Command

  // --- Response type owned by LobbyManager ---

  /** Paginated lobby-list result; adapted into [[GameManager.LobbiesListed]] by a GameManager message adapter. */
  final case class LobbiesListed(lobbies: List[LobbyMetadata], page: Int, limit: Int, total: Int)

  val recentlyEndedTtl: FiniteDuration = 1.hour

  private val logger = LoggerFactory.getLogger(getClass)

  /** Dispatches `io` asynchronously and logs a warning on failure. Does not block the actor. */
  private def persist(io: IO[Unit])(implicit runtime: IORuntime): Unit =
    io.unsafeRunAsync {
      case Left(err) => logger.warn(s"Redis lobby write failed: ${err.getMessage}")
      case Right(()) => ()
    }

  def apply(
      gameManager: ActorRef[GameManager.Command],
      lobbyRepo: LobbyRepository
  )(implicit runtime: IORuntime): Behavior[Command] = {
    val recentlyEnded: Cache[GameId, LobbyMetadata] = Scaffeine()
      .expireAfterWrite(recentlyEndedTtl)
      .build[GameId, LobbyMetadata]()
    running(Map.empty, recentlyEnded, Map.empty, gameManager, lobbyRepo)
  }

  /** Sends a [[PlayerEvent]] to every PlayerActor subscribed to the given lobby.
    *
    * Called after any state-changing lobby command (join, leave, cancellation, completion) so that all connected
    * players receive the update without the caller needing to know which actors are subscribed.
    */
  private def fanOut(
      subscribers: Map[GameId, Map[PlayerId, ActorRef[PlayerActor.Command]]],
      gameId: GameId,
      event: PlayerEvent
  ): Unit =
    subscribers.getOrElse(gameId, Map.empty).values.foreach(_ ! PlayerActor.SendEvent(event))

  /** Steady-state behavior holding all lobby data.
    *
    * @param lobbies map of active lobbies (WaitingForPlayers, ReadyToStart, InProgress)
    * @param recentlyEnded TTL cache of recently completed/cancelled lobbies, evicted after [[recentlyEndedTtl]]
    * @param subscribers per-lobby map from PlayerId to PlayerActor ref for players registered to receive push events
    * @param gameManager parent ref used to send [[GameManager.SpawnGame]] on a valid start request
    * @param lobbyRepo repository used for fire-and-forget persistence on every state change
    */
  private def running(
      lobbies: Map[GameId, LobbyMetadata],
      recentlyEnded: Cache[GameId, LobbyMetadata],
      subscribers: Map[GameId, Map[PlayerId, ActorRef[PlayerActor.Command]]],
      gameManager: ActorRef[GameManager.Command],
      lobbyRepo: LobbyRepository
  )(implicit runtime: IORuntime): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case RestoreLobbies(restored) =>
        val restoredMap = restored.map(m => m.gameId -> m).toMap
        context.log.info(s"Restoring ${restoredMap.size} lobbies from Redis")
        running(restoredMap, recentlyEnded, subscribers, gameManager, lobbyRepo)

      case CreateLobby(gameType, host, replyTo) =>
        context.log.info(s"Creating new lobby for game type $gameType with host ${host.name}")
        val lobby = LobbyMetadata.newLobby(gameType, host)
        replyTo ! GameManager.LobbyCreated(lobby.gameId, host)
        persist(lobbyRepo.saveLobby(lobby))
        running(lobbies + (lobby.gameId -> lobby), recentlyEnded, subscribers, gameManager, lobbyRepo)

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
              fanOut(subscribers, gameId, PlayerEvent.LobbyUpdated(updatedMetadata))
              persist(lobbyRepo.saveLobby(updatedMetadata))
              running(lobbies + (gameId -> updatedMetadata), recentlyEnded, subscribers, gameManager, lobbyRepo)
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
              fanOut(subscribers, gameId, PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled))
              replyTo ! GameManager.LobbyLeft(gameId, s"Lobby $gameId ended - host left")
              persist(lobbyRepo.deleteLobby(gameId))
              running(lobbies - gameId, recentlyEnded, subscribers - gameId, gameManager, lobbyRepo)
            } else {
              context.log.info(s"Player ${player.name} left lobby $gameId")
              replyTo ! GameManager.LobbyLeft(gameId, s"${player.name} left lobby $gameId")
              fanOut(subscribers, gameId, PlayerEvent.LobbyUpdated(newMetadata))
              val updatedSubscribers = subscribers.updatedWith(gameId)(_.map(_ - player.id))
              persist(lobbyRepo.saveLobby(newMetadata))
              running(lobbies + (gameId -> newMetadata), recentlyEnded, updatedSubscribers, gameManager, lobbyRepo)
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
            val lobbySubscribers = subscribers.getOrElse(gameId, Map.empty).values.toSet
            gameManager ! GameManager.SpawnGame(
              gameId,
              metadata.gameType,
              metadata.players.keySet,
              replyTo,
              lobbySubscribers
            )
            persist(lobbyRepo.saveLobby(updatedMetadata))
            running(lobbies + (gameId -> updatedMetadata), recentlyEnded, subscribers - gameId, gameManager, lobbyRepo)

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

      case ListLobbies(gameTypeFilter, page, limit, replyTo) =>
        context.log.info("Listing available lobbies")
        val all = lobbies.values.filter(_.status.isJoinable).toList
        val filtered = gameTypeFilter.fold(all)(gt => all.filter(_.gameType == gt))
        val total = filtered.size
        val paged = filtered.drop((page - 1) * limit).take(limit)
        replyTo ! LobbyManager.LobbiesListed(paged, page, limit, total)
        Behaviors.same

      case GetLobbyInfo(gameId, replyTo) =>
        context.log.info(s"Getting lobby metadata for game $gameId")
        lobbies.get(gameId).orElse(recentlyEnded.getIfPresent(gameId)) match {
          case Some(metadata) => replyTo ! GameManager.LobbyInfo(metadata)
          case None           => replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
        }
        Behaviors.same

      case SubscribeToLobby(gameId, playerId, playerRef, replyTo) =>
        lobbies.get(gameId) match {
          case Some(metadata) if metadata.status == GameLifecycleStatus.InProgress =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.GameAlreadyStarted(gameId))
            Behaviors.same
          case Some(metadata) =>
            val updated = subscribers.getOrElse(gameId, Map.empty) + (playerId -> playerRef)
            playerRef ! PlayerActor.SendEvent(PlayerEvent.LobbyUpdated(metadata))
            replyTo ! GameManager.SubscribeAcknowledged(gameId)
            running(lobbies, recentlyEnded, subscribers + (gameId -> updated), gameManager, lobbyRepo)
          case None =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
            Behaviors.same
        }

      case MarkCompleted(gameId, result) =>
        lobbies.get(gameId) match {
          case Some(metadata) =>
            context.log.info(s"Marking lobby $gameId as $result")
            val updated = metadata.copy(status = result)
            recentlyEnded.put(gameId, updated)
            fanOut(subscribers, gameId, PlayerEvent.GameEnded(result))
            persist(lobbyRepo.deleteLobby(gameId))
            running(lobbies - gameId, recentlyEnded, subscribers - gameId, gameManager, lobbyRepo)
          case None =>
            context.log.info(s"MarkCompleted for $gameId: no lobby entry (already removed or never restored)")
            Behaviors.same
        }
    }
  }
}
