package com.andy327.actor.core

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.github.blemale.scaffeine.{Cache, Scaffeine}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}

import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, LobbyRepository, Player}
import com.andy327.model.core.{GameId, GameType, PlayerId, RoomId}

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

  /** Remove `player` from a lobby. If the host leaves, the host role migrates to a remaining member and the lobby
    * survives; it is cancelled only when the host was the last player. Rejected with
    * [[lobby.LobbyError.GameInProgress]] once the game has started.
    */
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameManager.GameResponse])
      extends Command

  /** Cancel a pre-game lobby on behalf of its host, moving it to the recently-ended cache and notifying subscribers.
    * Replies with [[GameManager.LobbyLeft]] on success, [[GameManager.LobbyErrorResponse]] if the caller is not the
    * host or the lobby is unknown.
    */
  final case class CancelLobby(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameManager.GameResponse])
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

  /** Return the active, pre-game lobbies (WaitingForPlayers or ReadyToStart) that `playerId` has joined; replies with
    * [[PlayerLobbies]]. In-progress games are intentionally excluded — they are reported from
    * [[GameManager]]'s live-game index instead, so this never reports an InProgress lobby with no live game actor.
    */
  final case class ListLobbiesForPlayer(playerId: PlayerId, replyTo: ActorRef[LobbyManager.PlayerLobbies])
      extends Command

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

  /** Deregister `playerId` from `gameId`'s push events. Idempotent: replies with
    * [[GameManager.UnsubscribeAcknowledged]] even if the player was not subscribed or the lobby is unknown.
    */
  final case class UnsubscribeFromLobby(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameManager.GameResponse])
      extends Command

  // --- Internal commands (sent by GameManager, not reachable from HTTP) ---

  /** A match in `gameId`'s room has ended. Move the room to [[GameLifecycleStatus.Finished]] and re-own the match's
    * `subscribers` (handed back from the game actor) so the room can keep fanning out chat and the post-game state.
    * The room survives in memory for chat and rematch; its now-stale in-progress record is dropped from Redis.
    */
  final private[core] case class MatchEnded(
      gameId: RoomId,
      subscribers: Map[PlayerId, ActorRef[PlayerActor.Command]]
  ) extends Command

  /** Replace the in-memory lobby map with lobbies restored from Redis at startup. */
  final private[core] case class RestoreLobbies(lobbies: List[LobbyMetadata]) extends Command

  /** Fan `event` (a chat message) out to the lobby's subscribers, used while the match is still in its lobby phase. */
  final private[core] case class BroadcastChat(gameId: GameId, event: PlayerEvent) extends Command

  /** Periodic self-tick that tears down post-game (Finished) rooms that are empty or idle past [[finishedRoomTtl]]. */
  final private[core] case object EvictIdleRooms extends Command

  // --- Response type owned by LobbyManager ---

  /** Paginated lobby-list result; adapted into [[GameManager.LobbiesListed]] by a GameManager message adapter. */
  final case class LobbiesListed(lobbies: List[LobbyMetadata], page: Int, limit: Int, total: Int)

  /** A player's joined pre-game lobbies, replied to a [[ListLobbiesForPlayer]] query; consumed by [[GameManager]] while
    * assembling its combined [[GameManager.PlayerSessions]] response.
    */
  final case class PlayerLobbies(lobbies: List[LobbyMetadata])

  val recentlyEndedTtl: FiniteDuration = 1.hour

  /** A post-game (Finished) room with no connected players, or idle for longer than this, is torn down. */
  val finishedRoomTtl: FiniteDuration = 30.minutes

  /** How often the idle-room eviction runs. */
  private val evictInterval: FiniteDuration = 1.minute

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
  )(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.withTimers { timers =>
      timers.startTimerWithFixedDelay(EvictIdleRooms, evictInterval)
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

  /** Seat order for a match: the host first, then the remaining players ordered by id, rotated left by the room's
    * match count. Rotation makes the first-move seat (seat 0) alternate across rematches; the first match (count 0)
    * keeps the host in seat 0.
    */
  private def seatOrder(metadata: LobbyMetadata): Seq[PlayerId] = {
    val others = (metadata.players.keySet - metadata.hostId).toList.sortBy(_.toString)
    val roster = metadata.hostId :: others
    val rotation = metadata.matchCount % roster.size
    roster.drop(rotation) ::: roster.take(rotation)
  }

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
  )(implicit runtime: IORuntime): Behavior[Command] = Behaviors.receive[Command] { (context, message) =>
    message match {
      case RestoreLobbies(restored) =>
        // Only in-progress games are worth restoring: their game actor comes back from the database. Pre-game lobbies
        // (WaitingForPlayers/ReadyToStart) are dead across a restart — their players' sessions are gone — so drop and
        // delete them from Redis rather than leaving stale, un-rejoinable lobbies advertised in the list.
        val (keep, drop) = restored.partition(_.status == GameLifecycleStatus.InProgress)
        val restoredMap = keep.map(m => m.gameId -> m).toMap
        context.log.info(s"Restoring ${restoredMap.size} in-progress lobbies; deleting ${drop.size} dead pre-game ones")
        drop.foreach(m => persist(lobbyRepo.deleteLobby(m.gameId)))
        running(restoredMap, recentlyEnded, subscribers, gameManager, lobbyRepo)

      case BroadcastChat(gameId, event) =>
        fanOut(subscribers, gameId, event)
        // chat keeps a post-game room alive: bump its activity so eviction doesn't close it mid-conversation
        lobbies.get(gameId) match {
          case Some(m) =>
            running(
              lobbies + (gameId -> m.copy(lastActivityAt = Instant.now())),
              recentlyEnded,
              subscribers,
              gameManager,
              lobbyRepo
            )
          case None => Behaviors.same
        }

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
          // Leaving an in-progress game is a forfeit, handled by the game actor; GameManager routes those directly and
          // never forwards them here. This branch only guards the edge case where a lobby still shows InProgress but no
          // live game actor exists (e.g. mid-restore) — reject so the lobby cannot revert to a joinable status.
          case Some(metadata) if metadata.status == GameLifecycleStatus.InProgress =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.GameInProgress(gameId))
            Behaviors.same

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
              updatedPlayers.headOption match {
                case None =>
                  // host was the last player remaining — disband the lobby
                  context.log.info(s"Host (${player.name}) left lobby $gameId with no players left. Cancelling game...")
                  val cancelled = newMetadata.copy(status = GameLifecycleStatus.Cancelled)
                  recentlyEnded.put(gameId, cancelled)
                  fanOut(subscribers, gameId, PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled))
                  replyTo ! GameManager.LobbyLeft(gameId, s"Lobby $gameId ended - host left")
                  persist(lobbyRepo.deleteLobby(gameId))
                  running(lobbies - gameId, recentlyEnded, subscribers - gameId, gameManager, lobbyRepo)
                case Some((newHostId, newHost)) =>
                  // promote a remaining member to host so the lobby survives the host leaving (host migration)
                  context.log.info(s"Host (${player.name}) left lobby $gameId; transferring host to ${newHost.name}")
                  val migrated = newMetadata.copy(hostId = newHostId)
                  val msg = s"${player.name} left lobby $gameId; host transferred to ${newHost.name}"
                  replyTo ! GameManager.LobbyLeft(gameId, msg)
                  fanOut(subscribers, gameId, PlayerEvent.LobbyUpdated(migrated))
                  val updatedSubscribers = subscribers.updatedWith(gameId)(_.map(_ - player.id))
                  persist(lobbyRepo.saveLobby(migrated))
                  running(lobbies + (gameId -> migrated), recentlyEnded, updatedSubscribers, gameManager, lobbyRepo)
              }
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

      case CancelLobby(gameId, playerId, replyTo) =>
        lobbies.get(gameId) match {
          case Some(metadata) if metadata.hostId == playerId =>
            context.log.info(s"Host cancelled lobby $gameId")
            val cancelled = metadata.copy(status = GameLifecycleStatus.Cancelled)
            recentlyEnded.put(gameId, cancelled)
            fanOut(subscribers, gameId, PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled))
            replyTo ! GameManager.LobbyLeft(gameId, s"Lobby $gameId cancelled by host")
            persist(lobbyRepo.deleteLobby(gameId))
            running(lobbies - gameId, recentlyEnded, subscribers - gameId, gameManager, lobbyRepo)
          case Some(_) =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.NotHostError(gameId))
            Behaviors.same
          case None =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
            Behaviors.same
        }

      case StartGame(gameId, playerId, replyTo) =>
        // a host may start a ready pre-game lobby, or start a rematch from a finished room (same roster)
        def startable(status: GameLifecycleStatus): Boolean =
          status == GameLifecycleStatus.ReadyToStart || status == GameLifecycleStatus.Finished
        lobbies.get(gameId) match {
          case Some(metadata) if metadata.hostId == playerId && startable(metadata.status) =>
            val rematch = metadata.status == GameLifecycleStatus.Finished
            val newStatus = if (rematch) "rematch" else "start"
            context.log.info(s"Lobby $gameId validated for $newStatus — delegating to GM")
            // mint a fresh match id for this playthrough; the room id (gameId) stays stable across rematches
            val matchId = UUID.randomUUID()
            val updatedMetadata = metadata.copy(
              status = GameLifecycleStatus.InProgress,
              currentMatchId = Some(matchId),
              matchCount = metadata.matchCount + 1
            )
            val lobbySubscribers = subscribers.getOrElse(gameId, Map.empty)
            gameManager ! GameManager.SpawnGame(
              gameId,
              matchId,
              metadata.gameType,
              seatOrder(metadata),
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
        // newest first, so freshly created lobbies surface at the top of the (paginated) list
        val ordered = filtered.sortBy(_.createdAt)(Ordering[Instant].reverse)
        val total = ordered.size
        val paged = ordered.drop((page - 1) * limit).take(limit)
        replyTo ! LobbyManager.LobbiesListed(paged, page, limit, total)
        Behaviors.same

      case ListLobbiesForPlayer(playerId, replyTo) =>
        val mine = lobbies.values
          .filter(m => m.players.contains(playerId))
          .filter(m => m.status.isJoinable || m.status == GameLifecycleStatus.Finished)
          .toList
        replyTo ! LobbyManager.PlayerLobbies(mine)
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
            // watch the subscriber so a disconnect (its PlayerActor stopping) drops it from the map automatically,
            // mirroring TurnBasedGameActor; without this a dead ref would linger and receive dead-letter fan-out
            context.watch(playerRef)
            val updated = subscribers.getOrElse(gameId, Map.empty) + (playerId -> playerRef)
            playerRef ! PlayerActor.SendEvent(PlayerEvent.LobbyUpdated(metadata))
            replyTo ! GameManager.SubscribeAcknowledged(gameId)
            running(lobbies, recentlyEnded, subscribers + (gameId -> updated), gameManager, lobbyRepo)
          case None =>
            replyTo ! GameManager.LobbyErrorResponse(LobbyError.LobbyNotFound(gameId))
            Behaviors.same
        }

      case UnsubscribeFromLobby(gameId, playerId, replyTo) =>
        // idempotent: unwatch and drop the player's ref if present, otherwise a harmless no-op; either way ack
        subscribers.getOrElse(gameId, Map.empty).get(playerId).foreach(context.unwatch)
        val updated = subscribers.updatedWith(gameId)(_.map(_ - playerId).filter(_.nonEmpty))
        replyTo ! GameManager.UnsubscribeAcknowledged(gameId)
        running(lobbies, recentlyEnded, updated, gameManager, lobbyRepo)

      case MatchEnded(gameId, returnedSubscribers) =>
        lobbies.get(gameId) match {
          case Some(metadata) =>
            context.log.info(s"Match in room $gameId ended; returning ${returnedSubscribers.size} players to the room")
            val finished = metadata.copy(status = GameLifecycleStatus.Finished, lastActivityAt = Instant.now())
            // re-own the match's subscribers (the game actor already sent them GameEnded before handing them back) and
            // watch each so a later disconnect drops it, mirroring SubscribeToLobby
            returnedSubscribers.values.foreach(context.watch)
            val merged = subscribers.getOrElse(gameId, Map.empty) ++ returnedSubscribers
            // tell everyone in the room it is now in its post-game state (drives the rematch process)
            merged.values.foreach(_ ! PlayerActor.SendEvent(PlayerEvent.LobbyUpdated(finished)))
            // the post-game room lives in memory only; drop the stale in-progress record so a restart won't revive it
            persist(lobbyRepo.deleteLobby(gameId))
            running(
              lobbies + (gameId -> finished),
              recentlyEnded,
              subscribers + (gameId -> merged),
              gameManager,
              lobbyRepo
            )
          case None =>
            context.log.info(s"MatchEnded for $gameId: no lobby entry (orphan match); dropping returned subscribers")
            Behaviors.same
        }

      case EvictIdleRooms =>
        // a post-game room is torn down once everyone has left it or it has sat idle past the TTL
        val cutoff = Instant.now().toEpochMilli - finishedRoomTtl.toMillis
        val stale = lobbies.filter { case (id, m) =>
          m.status == GameLifecycleStatus.Finished &&
          (subscribers.getOrElse(id, Map.empty).isEmpty || m.lastActivityAt.toEpochMilli < cutoff)
        }
        if (stale.isEmpty) Behaviors.same
        else {
          stale.foreach { case (id, m) =>
            context.log.info(s"Evicting idle/empty finished room $id")
            // tell any remaining watchers the room has closed, then retire it to the recently-ended cache
            fanOut(subscribers, id, PlayerEvent.GameEnded(GameLifecycleStatus.Cancelled))
            recentlyEnded.put(id, m.copy(status = GameLifecycleStatus.Cancelled))
          }
          running(lobbies -- stale.keySet, recentlyEnded, subscribers -- stale.keySet, gameManager, lobbyRepo)
        }
    }
  }.receiveSignal { case (context, Terminated(ref)) =>
    // a subscribed PlayerActor stopped (disconnect/reconnect); drop its now-dead ref from every lobby's subscriber set
    context.log.debug(s"Lobby subscriber $ref terminated; removing from all lobby subscriber sets")
    val cleaned = subscribers.view
      .mapValues(_.filterNot { case (_, r) => r == ref })
      .filter { case (_, refs) => refs.nonEmpty }
      .toMap
    running(lobbies, recentlyEnded, cleaned, gameManager, lobbyRepo)
  }
}
