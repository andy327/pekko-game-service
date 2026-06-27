package com.andy327.actor.core

import java.time.Instant

import scala.concurrent.duration._
import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.util.Timeout

import com.andy327.actor.chat.{ChatRepository, NoOpChatRepository}
import com.andy327.actor.events.{EventPublisher, GameEvent, NoOpEventPublisher}
import com.andy327.actor.game.{GameOperation, GameRegistry, GameState}
import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, LobbyRepository, Player}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{Game, GameError, GameId, GameType, MatchId, PlayerId, RoomId}
import com.andy327.persistence.db.{GameRepository, MoveHistoryRepository, MoveRecord, NoOpMoveHistoryRepository}

/** A supervisor actor responsible for game actor lifecycle and request routing.
  *
  * GameManager owns the map of live game actors and handles actor spawning, game operation forwarding, and game
  * completion. Lobby lifecycle state is delegated to a LobbyManager child actor. Player session tracking is delegated
  * to a PlayerManager child actor.
  *
  * On startup, GameManager asynchronously restores persisted games from the database, stashing incoming messages until
  * restoration is complete.
  *
  * When a game completes, its actor is stopped and the game ID is moved to a completed set. Subsequent status queries
  * for completed games are served directly from the database.
  *
  * Actor relationships:
  *   - Parent: root (top-level, created by `GameServer`)
  *   - Children: [[LobbyManager]], [[PlayerManager]], one game actor per active game
  *   - Receives from: HTTP route handlers (all public `Command` messages)
  *   - Sends to: [[LobbyManager]] (lobby commands), [[PlayerManager]] (player session commands), game actors (game
  *     operations via [[GameActor.GameCommand]]), [[com.andy327.actor.persistence.PersistenceProtocol]]
  *     (`SaveSnapshot` on game start)
  */
object GameManager {
  sealed trait Command

  // --- Lobby commands (forwarded verbatim to LobbyManager) ---

  /** Create a new lobby; replies with [[LobbyCreated]]. */
  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameResponse]) extends Command

  /** Join an existing lobby; replies with [[LobbyJoined]] or a [[LobbyErrorResponse]]. */
  final case class JoinLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command

  /** Leave a lobby. If the host leaves, the host role migrates to a remaining member and the lobby survives; it is
    * cancelled only when the host was the last player. Rejected with
    * [[com.andy327.actor.lobby.LobbyError.GameInProgress]] once the game has started (leaving is then a forfeit).
    */
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command

  /** Cancel a pre-game lobby on behalf of its host; replies with [[LobbyLeft]] on success or a [[LobbyErrorResponse]]
    * (not host, lobby not found). Rejected once the game has started — at that point leaving is a forfeit.
    */
  final case class CancelLobby(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse]) extends Command

  /** Start a game in a lobby the caller hosts; replies with [[GameStarted]] or a [[LobbyErrorResponse]]. */
  final case class StartGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse]) extends Command

  /** List joinable lobbies with optional game-type filter and pagination; replies with [[LobbiesListed]]. */
  final case class ListLobbies(
      gameType: Option[GameType],
      page: Int,
      limit: Int,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Fetch metadata for a specific lobby (active or recently ended); replies with [[LobbyInfo]]. */
  final case class GetLobbyInfo(gameId: GameId, replyTo: ActorRef[GameResponse]) extends Command

  /** Subscribe the authenticated player to lobby push events as a spectator; replies with [[SubscribeAcknowledged]] or
    * [[ErrorResponse]].
    */
  final case class SubscribePlayerToLobby(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse])
      extends Command

  /** Stop spectating a lobby: deregister the authenticated player from [[LobbyManager]]'s push events. Idempotent —
    * replies with [[UnsubscribeAcknowledged]] even if the player was not subscribed.
    */
  final case class UnsubscribePlayerFromLobby(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse])
      extends Command

  /** Return the caller's current participation — joined pre-game lobbies and live games they are seated in; replies
    * with [[PlayerSessions]]. Lobbies are fetched from [[LobbyManager]] via an internal ask; games are read from
    * GameManager's own live-game index. Strictly current state: completed games belong to the player-history endpoint.
    */
  final case class GetPlayerSessions(playerId: PlayerId, replyTo: ActorRef[GameResponse]) extends Command

  // --- Game operation commands (routed to a specific game actor via GameRegistry) ---

  /** Forward `op` to the game actor for `gameId`; replies with [[GameStatus]], [[GameNotFound]], [[MoveRejected]],
    * or [[ErrorResponse]] (internal failure).
    */
  final case class RunGameOperation(gameId: GameId, op: GameOperation, replyTo: ActorRef[GameResponse]) extends Command

  /** Fetch the ordered move history for `gameId` from the move log; replies with [[MoveHistory]] (empty if none) or
    * [[ErrorResponse]] on a storage failure. Served by a direct DB read, so it works for active and finished games.
    */
  final case class GetMoveHistory(gameId: GameId, replyTo: ActorRef[GameResponse]) extends Command

  /** Fetch the recent chat history for `gameId` (backscroll) from the chat store; replies with [[ChatHistory]] (empty
    * if none) or [[ErrorResponse]] on a storage failure. Served by a direct read, so it works for active and finished
    * games; live messages continue to arrive over the WebSocket.
    */
  final case class GetChatHistory(gameId: GameId, replyTo: ActorRef[GameResponse]) extends Command

  /** Post a chat message to a match: fans it out to the match's subscribers (the game actor's while in progress, the
    * lobby's otherwise) and emits a `ChatSent` analytics event.
    */
  final case class SendChat(gameId: GameId, sender: Player, text: String) extends Command

  /** Subscribe the authenticated player to game push events as a spectator; replies with [[SubscribeAcknowledged]] or
    * [[ErrorResponse]].
    */
  final case class SubscribePlayerToGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse])
      extends Command

  /** Stop spectating a game: deregister the authenticated player from the game actor's push events. Idempotent —
    * replies with [[UnsubscribeAcknowledged]] even if the player was not subscribed or the game is no longer active.
    */
  final case class UnsubscribePlayerFromGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse])
      extends Command

  // --- Player session commands (forwarded to PlayerManager) ---

  /** Register (or reconnect) a player; replies with the spawned [[PlayerActor]] ref. */
  final case class RegisterPlayer(
      player: Player,
      sessionOut: ActorRef[PlayerActor.SessionOutput],
      replyTo: ActorRef[ActorRef[PlayerActor.Command]]
  ) extends Command

  /** Clean up the PlayerActor and session state for a disconnected player.
    *
    * `playerRef` identifies the session whose stream terminated; [[PlayerManager]] ignores the message if the player
    * has since reconnected with a new PlayerActor.
    */
  final case class PlayerDisconnected(playerId: PlayerId, playerRef: ActorRef[PlayerActor.Command]) extends Command

  // --- Lifecycle commands ---

  /** Sent by a game actor when its match reaches a terminal state (won or draw). Carries the `matchId` that just ended,
    * the `roomId` hosting it, and the match's `subscribers` (by playerId) so GameManager can hand them back to the
    * room, which then survives as a post-game room for chat and rematch.
    */
  final case class GameCompleted(
      matchId: MatchId,
      roomId: RoomId,
      result: GameLifecycleStatus.GameEnded,
      subscribers: Map[PlayerId, ActorRef[PlayerActor.Command]] = Map.empty
  ) extends Command

  // --- Internal commands (not reachable from HTTP) ---

  /** Carries the result of the async restore initiated at startup; transitions from initializing to running. */
  final protected[core] case class RestoreGames(
      games: Map[GameId, (GameType, Game[_, _, _, _, _])],
      lobbies: List[LobbyMetadata]
  ) extends Command

  /** Sent by LobbyManager after validating a StartGame request; GameManager spawns the child actor. Carries the stable
    * `roomId`, the freshly-minted `matchId` for this playthrough, and the seated `players` in turn order (seat 0 moves
    * first); LobbyManager rotates that order across rematches.
    */
  final private[core] case class SpawnGame(
      roomId: RoomId,
      matchId: MatchId,
      gameType: GameType,
      players: Seq[PlayerId],
      replyTo: ActorRef[GameResponse],
      subscribers: Map[PlayerId, ActorRef[PlayerActor.Command]] = Map.empty
  ) extends Command

  /** Adapter wrapper — converts a game actor's `Either[GameError, GameState]` reply into a [[GameResponse]]. */
  final private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

  /** Adapter wrapper — converts a game actor's forfeit reply (from a `LeaveLobby` on an in-progress game) into a
    * [[GameForfeited]] on success or a [[MoveRejected]] if the model rejected the leave.
    */
  final private case class WrappedForfeitResponse(
      response: Either[GameError, GameState],
      gameId: GameId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Adapter wrapper — converts [[LobbyManager.LobbiesListed]] into a [[GameResponse]] for the HTTP caller. */
  final private case class WrappedLobbiesListed(listed: LobbyManager.LobbiesListed, replyTo: ActorRef[GameResponse])
      extends Command

  /** Intercepts a [[LobbyManager]] response for [[CreateLobby]] or [[JoinLobby]] before forwarding to the caller,
    * carrying the requesting player's ID so GameManager can look up their actor ref and auto-subscribe them.
    */
  final private case class LobbyResponseIntercepted(
      response: GameResponse,
      playerId: PlayerId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries [[LobbyManager]]'s reply to the lobby half of a [[GetPlayerSessions]] request, alongside the games already
    * resolved from the live-game index, so the two can be combined into a single [[PlayerSessions]] reply.
    */
  final private case class PlayerSessionsReady(
      lobbies: List[LobbyMetadata],
      games: List[ActiveGameSummary],
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries the result of a [[PlayerManager.LookupPlayer]] ask for a [[SubscribePlayerToLobby]] request. */
  final private case class PlayerRefForLobbySpectate(
      playerRef: Option[ActorRef[PlayerActor.Command]],
      gameId: GameId,
      playerId: PlayerId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries the result of a [[PlayerManager.LookupPlayer]] ask for a [[SubscribePlayerToGame]] request. */
  final private case class PlayerRefForGameSpectate(
      playerRef: Option[ActorRef[PlayerActor.Command]],
      gameId: GameId,
      playerId: PlayerId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries the result of a [[PlayerManager.LookupPlayer]] ask for an [[UnsubscribePlayerFromGame]] request. */
  final private case class PlayerRefForGameUnsubscribe(
      playerRef: Option[ActorRef[PlayerActor.Command]],
      gameId: GameId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries the result of a [[PlayerManager.LookupPlayer]] ask initiated during auto-subscribe on lobby create/join.
    */
  final private case class PlayerRefForSubscribe(
      playerRef: Option[ActorRef[PlayerActor.Command]],
      gameId: GameId,
      playerId: PlayerId,
      response: GameResponse,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Result of a DB fallback load for a completed game's current state. */
  final private case class CompletedGameLoaded(
      result: Either[Throwable, Option[Game[_, _, _, _, _]]],
      gameId: GameId,
      gameType: GameType,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries the result of an async move-history load for a [[GetMoveHistory]] request. */
  final private case class MoveHistoryLoaded(
      result: Either[Throwable, List[MoveRecord]],
      gameId: GameId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  /** Carries the result of an async chat-history load for a [[GetChatHistory]] request. */
  final private case class ChatHistoryLoaded(
      result: Either[Throwable, List[PlayerEvent.ChatMessage]],
      gameId: GameId,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  // --- Response types (sent back to HTTP route handlers) ---

  sealed trait GameResponse

  // Lobby responses
  final case class LobbyCreated(gameId: GameId, host: Player) extends GameResponse
  final case class LobbyJoined(gameId: GameId, metadata: LobbyMetadata, joinedPlayer: Player) extends GameResponse
  final case class LobbyLeft(gameId: GameId, message: String) extends GameResponse
  final case class LobbyInfo(metadata: LobbyMetadata) extends GameResponse

  /** Paginated list of joinable lobbies. */
  final case class LobbiesListed(lobbies: List[LobbyMetadata], page: Int, limit: Int, total: Int) extends GameResponse

  /** A single live game the requesting player is seated in, carried by [[PlayerSessions]]. Deliberately minimal — just
    * the id and kind, enough to re-discover and reconnect to the match; full state is fetched via the game endpoint.
    */
  final case class ActiveGameSummary(gameId: GameId, gameType: GameType)

  /** The caller's current participation: joined pre-game `lobbies` and the live `games` they are seated in. Either list
    * may be empty. Serialized directly to the wire (no separate response DTO), like [[LobbiesListed]].
    */
  final case class PlayerSessions(lobbies: List[LobbyMetadata], games: List[ActiveGameSummary]) extends GameResponse

  // Game responses
  final case class GameStarted(gameId: GameId) extends GameResponse
  final case class GameStatus(state: GameState) extends GameResponse

  /** A player left an in-progress game and thereby forfeited it; `state` is the leaver's view of the finished game. */
  final case class GameForfeited(gameId: GameId, state: GameState) extends GameResponse

  /** The ordered move history for a game; `moves` is empty if the game has no recorded moves. */
  final case class MoveHistory(gameId: GameId, moves: List[MoveRecord]) extends GameResponse

  /** The recent chat history for a game, oldest first; `messages` is empty if the game has no recorded messages. */
  final case class ChatHistory(gameId: GameId, messages: List[PlayerEvent.ChatMessage]) extends GameResponse

  // Error responses

  /** No game exists for the requested ID (neither active nor in completed-game storage). Maps to HTTP 404. */
  final case class GameNotFound(gameId: GameId) extends GameResponse

  /** The game exists but rejected the operation (wrong turn, illegal move, game already over). Maps to HTTP 409. */
  final case class MoveRejected(message: String) extends GameResponse

  final case class LobbyErrorResponse(error: LobbyError) extends GameResponse
  final case class ErrorResponse(message: String) extends GameResponse

  /** Sent in response to a successful [[SubscribePlayerToLobby]] or [[SubscribePlayerToGame]] request. */
  final case class SubscribeAcknowledged(gameId: GameId) extends GameResponse

  /** Sent in response to a successful [[UnsubscribePlayerFromGame]] or [[UnsubscribePlayerFromLobby]] request. */
  final case class UnsubscribeAcknowledged(gameId: GameId) extends GameResponse

  /** Emitted once when the DB restore is complete; used in tests to await the running state. */
  case object Ready extends GameResponse

  /** Timeout for internal `context.ask` calls used to look up player refs during subscribe flows. */
  private val subscribeAskTimeout: Timeout = Timeout(3.seconds)

  /** Look up the live PlayerActor for `playerId` and hand the result (`None` if not connected) to `onResolved`, which
    * builds the follow-up command sent back to self. Centralizes the spectate/auto-subscribe lookups so the ask wiring
    * — and its single ask-timeout fallback (mapped, like a miss, to `None`) — lives in one place.
    */
  private def askPlayerRef(
      context: ActorContext[Command],
      playerManager: ActorRef[PlayerManager.Command],
      playerId: PlayerId
  )(onResolved: Option[ActorRef[PlayerActor.Command]] => Command): Unit = {
    implicit val t: Timeout = subscribeAskTimeout
    context.ask(playerManager, PlayerManager.LookupPlayer(playerId, _)) {
      case Success(ref) => onResolved(ref)
      case Failure(_)   => onResolved(None)
    }
  }

  /** Create the GameManager, kick off an async DB restore, and stash messages until restoration completes.
    *
    * @param persistActor shared actor for all persistence I/O
    * @param gameRepo the repository used for the initial game restore and completed-game state lookups
    * @param lobbyRepo the repository used to restore lobbies at startup and persist lobby changes
    * @param moveRepo the repository read to serve move-history queries; defaults to no-op (empty history)
    * @param chatRepo the repository written on each chat message and read to serve backscroll; defaults to no-op
    * @param publisher emit seam for analytics events (game started/move made/game completed/chat sent); defaults to
    *                  no-op
    * @param onReady optional ref that receives a [[Ready]] signal once the running state is entered (used in tests)
    */
  @annotation.nowarn("msg=match may not be exhaustive")
  def apply(
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      lobbyRepo: LobbyRepository,
      moveRepo: MoveHistoryRepository = NoOpMoveHistoryRepository,
      chatRepo: ChatRepository = NoOpChatRepository,
      publisher: EventPublisher = NoOpEventPublisher,
      onReady: Option[ActorRef[Ready.type]] = None
  )(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.withStash(capacity = 128) { stash =>
      Behaviors.setup { context =>
        val lobbyManager = context.spawn(LobbyManager(context.self, lobbyRepo), "lobby-manager")
        val playerManager = context.spawn(PlayerManager(), "player-manager")

        val restoreIO = for {
          games <- IO.defer(gameRepo.loadAllGames())
          lobbies <- IO.defer(lobbyRepo.loadAllLobbies())
        } yield (games, lobbies)

        context.pipeToSelf(restoreIO.attempt.unsafeToFuture()) {
          case Success(Right((games, lobbies))) =>
            context.log.info(s"Restoring ${games.size} games and ${lobbies.size} lobbies")
            RestoreGames(games, lobbies)
          case Success(Left(ex)) =>
            context.log.error("Failed to restore state from storage", ex)
            RestoreGames(Map.empty, Nil)
        }

        initializing(
          lobbyManager,
          playerManager,
          persistActor,
          gameRepo,
          moveRepo,
          chatRepo,
          stash,
          publisher,
          onReady
        )
      }
    }

  /** Startup behavior: stashes all incoming messages until the async DB restore completes.
    *
    * Waits for a single [[RestoreGames]] message, spawns actors for every recovered game, then unstashes
    * all buffered messages and transitions to [[running]].
    *
    * @param lobbyManager child actor owning all lobby/room lifecycle state
    * @param playerManager child actor tracking connected players' sessions (their PlayerActor refs)
    * @param persistActor shared actor for all persistence I/O (snapshots, move log, results)
    * @param gameRepo repository used for the initial game restore and completed-game state lookups
    * @param moveRepo repository read to serve move-history queries
    * @param chatRepo repository written on each chat message and read to serve backscroll
    * @param stash buffer holding commands received before the restore completes; drained into [[running]]
    * @param publisher emit seam for analytics events (game started/move made/game completed/chat sent)
    * @param onReady optional ref signalled once the running state is entered (used in tests)
    */
  private def initializing(
      lobbyManager: ActorRef[LobbyManager.Command],
      playerManager: ActorRef[PlayerManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      moveRepo: MoveHistoryRepository,
      chatRepo: ChatRepository,
      stash: StashBuffer[Command],
      publisher: EventPublisher,
      onReady: Option[ActorRef[Ready.type]]
  )(implicit runtime: IORuntime): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case RestoreGames(games, lobbies) =>
        // a persisted game is keyed by its matchId; recover the room hosting it from the lobby that recorded that match
        // as current. An orphan match (no such lobby — e.g. its lobby was lost from Redis) falls back to a self-room
        // keyed by the matchId so the game is still reachable.
        val matchToRoom: Map[MatchId, RoomId] =
          lobbies.flatMap(m => m.currentMatchId.map(_ -> m.gameId)).toMap

        // resolve each restored game to its room once, then derive the actor map and the player index from it
        val resolved = games.toList.map { case (matchId, (gameType, game)) =>
          val roomId = matchToRoom.getOrElse(matchId, matchId)
          if (!matchToRoom.contains(matchId))
            context.log.warn(s"Restored match $matchId has no owning lobby; treating it as its own room")
          (roomId, matchId, gameType, game)
        }

        val restoredActors = resolved.map { case (roomId, matchId, gameType, game) =>
          val gameActor = GameRegistry.forType(gameType).actor
          val behavior = gameActor.fromSnapshot(matchId, roomId, game, persistActor, context.self, publisher)
          val actorRef = context.spawn(behavior, s"game-$matchId").unsafeUpcast[GameActor.GameCommand]
          roomId -> (gameType, matchId, actorRef)
        }.toMap

        // Rebuild the player -> live-rooms index from each restored game's roster, so a reconnecting player can
        // rediscover in-flight matches that survived a restart.
        val restoredPlayerGames = resolved.foldLeft(Map.empty[PlayerId, Set[RoomId]]) {
          case (acc, (roomId, _, _, game)) =>
            game.players.foldLeft(acc)((idx, pid) => idx.updated(pid, idx.getOrElse(pid, Set.empty) + roomId))
        }

        context.log.info(s"Initialized ${restoredActors.size} game actors from snapshots")
        lobbyManager ! LobbyManager.RestoreLobbies(lobbies)
        onReady.foreach(_ ! Ready)
        stash.unstashAll(
          running(
            restoredActors,
            Map.empty,
            restoredPlayerGames,
            lobbyManager,
            playerManager,
            persistActor,
            gameRepo,
            moveRepo,
            chatRepo,
            publisher
          )(runtime)
        )

      case other =>
        stash.stash(other)
        Behaviors.same
    }
  }

  /** Steady-state behavior: routes commands to child actors and owns the active game registry.
    *
    * Lobby and player session commands are forwarded to [[LobbyManager]] and [[PlayerManager]] respectively.
    * Game operation commands are dispatched to the appropriate game actor via [[GameRegistry]].
    *
    * @param activeGames map from RoomId to (GameType, matchId, game actor ref) for the live match in each running room
    * @param completedMatch retains the (matchId, GameType) of each room's finished match so GetState/history queries
    *                       can fall back to the DB by match id after the actor has stopped
    * @param playerGames reverse index from PlayerId to the set of live rooms they are seated in, used to answer
    *                    "which games am I in?" without scanning every room; populated at spawn/restore, pruned on
    *                    completion
    * @param lobbyManager child actor owning all lobby/room lifecycle state
    * @param playerManager child actor tracking connected players' sessions (their PlayerActor refs)
    * @param persistActor shared actor for all persistence I/O (snapshots, move log, results)
    * @param gameRepo repository used for completed-game state lookups (the DB fallback for GetState)
    * @param moveRepo repository read to serve move-history queries
    * @param chatRepo repository written on each chat message and read to serve backscroll
    * @param publisher emit seam for analytics events (game started/move made/game completed/chat sent)
    */
  private def running(
      activeGames: Map[RoomId, (GameType, MatchId, ActorRef[GameActor.GameCommand])],
      completedMatch: Map[RoomId, (MatchId, GameType)],
      playerGames: Map[PlayerId, Set[RoomId]],
      lobbyManager: ActorRef[LobbyManager.Command],
      playerManager: ActorRef[PlayerManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      moveRepo: MoveHistoryRepository,
      chatRepo: ChatRepository,
      publisher: EventPublisher
  )(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case CreateLobby(gameType, host, replyTo) =>
          val adapter = context.messageAdapter[GameResponse](LobbyResponseIntercepted(_, host.id, replyTo))
          lobbyManager ! LobbyManager.CreateLobby(gameType, host, adapter)
          Behaviors.same

        case JoinLobby(gameId, player, replyTo) =>
          val adapter = context.messageAdapter[GameResponse](LobbyResponseIntercepted(_, player.id, replyTo))
          lobbyManager ! LobbyManager.JoinLobby(gameId, player, adapter)
          Behaviors.same

        case LeaveLobby(gameId, player, replyTo) =>
          activeGames.get(gameId) match {
            // game in progress: leaving it is a forfeit, handled by the game actor (which then self-completes and
            // triggers GameCompleted -> MarkCompleted, moving the lobby to Completed)
            case Some((gameType, _, gameActor)) =>
              val adaptedRef: ActorRef[Either[GameError, GameState]] =
                context.messageAdapter(response => WrappedForfeitResponse(response, gameId, replyTo))
              gameActor ! GameRegistry.forType(gameType).actor.forfeitCommand(player.id, adaptedRef)
            // pre-game (or unknown) lobby: ordinary leave / host-cancel handled by LobbyManager as before
            case None =>
              lobbyManager ! LobbyManager.LeaveLobby(gameId, player, replyTo)
          }
          Behaviors.same

        case CancelLobby(gameId, playerId, replyTo) =>
          // an in-progress game cannot be cancelled — at that point a host leaving is a forfeit, not a lobby cancel
          if (activeGames.contains(gameId))
            replyTo ! LobbyErrorResponse(LobbyError.GameInProgress(gameId))
          else
            lobbyManager ! LobbyManager.CancelLobby(gameId, playerId, replyTo)
          Behaviors.same

        case StartGame(gameId, playerId, replyTo) =>
          lobbyManager ! LobbyManager.StartGame(gameId, playerId, replyTo)
          Behaviors.same

        case ListLobbies(gameType, page, limit, replyTo) =>
          val adapter = context.messageAdapter[LobbyManager.LobbiesListed](r => WrappedLobbiesListed(r, replyTo))
          lobbyManager ! LobbyManager.ListLobbies(gameType, page, limit, adapter)
          Behaviors.same

        case GetLobbyInfo(gameId, replyTo) =>
          lobbyManager ! LobbyManager.GetLobbyInfo(gameId, replyTo)
          Behaviors.same

        case LobbyResponseIntercepted(response, playerId, replyTo) =>
          val maybeGameId = response match {
            case LobbyCreated(gameId, _)   => Some(gameId)
            case LobbyJoined(gameId, _, _) => Some(gameId)
            case _                         => None
          }
          maybeGameId match {
            case Some(gameId) =>
              askPlayerRef(context, playerManager, playerId) { ref =>
                PlayerRefForSubscribe(ref, gameId, playerId, response, replyTo)
              }
            case None =>
              replyTo ! response
          }
          Behaviors.same

        case PlayerRefForSubscribe(playerRefOpt, gameId, playerId, response, replyTo) =>
          playerRefOpt.foreach(ref =>
            lobbyManager ! LobbyManager.SubscribeToLobby(gameId, playerId, ref, context.system.ignoreRef)
          )
          replyTo ! response
          Behaviors.same

        case SubscribePlayerToLobby(gameId, playerId, replyTo) =>
          askPlayerRef(context, playerManager, playerId) { ref =>
            PlayerRefForLobbySpectate(ref, gameId, playerId, replyTo)
          }
          Behaviors.same

        case PlayerRefForLobbySpectate(playerRefOpt, gameId, playerId, replyTo) =>
          playerRefOpt match {
            case Some(ref) =>
              lobbyManager ! LobbyManager.SubscribeToLobby(gameId, playerId, ref, replyTo)
            case None =>
              context.log.warn(s"SubscribePlayerToLobby: player $playerId is not connected")
              replyTo ! ErrorResponse("Player is not connected via WebSocket")
          }
          Behaviors.same

        case UnsubscribePlayerFromLobby(gameId, playerId, replyTo) =>
          // LobbyManager keys subscribers by playerId, so no PlayerActor lookup is needed; it acks directly.
          lobbyManager ! LobbyManager.UnsubscribeFromLobby(gameId, playerId, replyTo)
          Behaviors.same

        case GetPlayerSessions(playerId, replyTo) =>
          // resolve the player's live games from the reverse index, intersected with activeGames so a stale entry can
          // never surface a game that is no longer running
          val games = playerGames.getOrElse(playerId, Set.empty).toList.flatMap { roomId =>
            activeGames.get(roomId).map { case (gameType, _, _) => ActiveGameSummary(roomId, gameType) }
          }
          implicit val t: Timeout = subscribeAskTimeout
          context.ask(lobbyManager, LobbyManager.ListLobbiesForPlayer(playerId, _)) {
            case Success(LobbyManager.PlayerLobbies(lobbies)) => PlayerSessionsReady(lobbies, games, replyTo)
            case Failure(_)                                   => PlayerSessionsReady(Nil, games, replyTo)
          }
          Behaviors.same

        case PlayerSessionsReady(lobbies, games, replyTo) =>
          replyTo ! PlayerSessions(lobbies, games)
          Behaviors.same

        case SendChat(gameId, sender, text) =>
          val event = PlayerEvent.ChatMessage(gameId, sender.id, sender.name, text, Instant.now())
          val liveMatch = activeGames.get(gameId)
          liveMatch match {
            case Some((gameType, _, gameActor)) =>
              gameActor ! GameRegistry.forType(gameType).actor.broadcastCommand(event)
            case None =>
              // not in progress (or unknown): fan out via the lobby; a bogus gameId is a harmless no-op there
              lobbyManager ! LobbyManager.BroadcastChat(gameId, event)
          }
          // record the send for analytics (gameType is None for lobby chat, before the game starts)
          publisher.publish(GameEvent.ChatSent(gameId, liveMatch.map(_._1)))
          // record to the bounded chat history so late joiners can backscroll; best-effort, never blocks the send
          chatRepo.append(event).unsafeRunAsync {
            case Left(ex) => context.log.warn(s"Failed to persist chat message for $gameId", ex)
            case Right(_) => ()
          }
          Behaviors.same

        case SubscribePlayerToGame(gameId, playerId, replyTo) =>
          askPlayerRef(context, playerManager, playerId) { ref =>
            PlayerRefForGameSpectate(ref, gameId, playerId, replyTo)
          }
          Behaviors.same

        case PlayerRefForGameSpectate(playerRefOpt, gameId, playerId, replyTo) =>
          playerRefOpt match {
            case Some(ref) =>
              activeGames.get(gameId) match {
                case Some((gameType, _, gameActor)) =>
                  gameActor ! GameRegistry.forType(gameType).actor.subscribeCommand(ref, playerId)
                  replyTo ! SubscribeAcknowledged(gameId)
                case None =>
                  replyTo ! ErrorResponse(s"No active game found for $gameId")
              }
            case None =>
              context.log.warn(s"SubscribePlayerToGame: player is not connected for game $gameId")
              replyTo ! ErrorResponse("Player is not connected via WebSocket")
          }
          Behaviors.same

        case UnsubscribePlayerFromGame(gameId, playerId, replyTo) =>
          askPlayerRef(context, playerManager, playerId) { ref =>
            PlayerRefForGameUnsubscribe(ref, gameId, replyTo)
          }
          Behaviors.same

        case PlayerRefForGameUnsubscribe(playerRefOpt, gameId, replyTo) =>
          // Unsubscribe is best-effort and idempotent: if the player is still connected and the game is still active,
          // drop their session from the game actor's subscriber set; otherwise there is nothing to remove. Either way
          // the DELETE succeeds, so a client can safely call it without first checking its own subscription state.
          (playerRefOpt, activeGames.get(gameId)) match {
            case (Some(ref), Some((gameType, _, gameActor))) =>
              gameActor ! GameRegistry.forType(gameType).actor.unsubscribeCommand(ref)
            case _ =>
              ()
          }
          replyTo ! UnsubscribeAcknowledged(gameId)
          Behaviors.same

        case RegisterPlayer(player, sessionOut, replyTo) =>
          playerManager ! PlayerManager.RegisterPlayer(player, sessionOut, replyTo)
          Behaviors.same

        case PlayerDisconnected(playerId, playerRef) =>
          playerManager ! PlayerManager.PlayerDisconnected(playerId, playerRef)
          Behaviors.same

        case SpawnGame(roomId, matchId, gameType, players, replyTo, subscribers) =>
          val n = players.size
          if (n < gameType.minPlayers || n > gameType.maxPlayers) {
            val expected =
              if (gameType.minPlayers == gameType.maxPlayers) s"${gameType.minPlayers}"
              else s"${gameType.minPlayers}–${gameType.maxPlayers}"
            context.log.error(s"SpawnGame rejected for room $roomId: $n players supplied, expected $expected")
            replyTo ! ErrorResponse(s"$expected players required, got $n")
            Behaviors.same
          } else {
            val bundle = GameRegistry.forType(gameType)
            val (game, behavior) =
              bundle.actor.create(matchId, roomId, players, persistActor, context.self, publisher)
            val actorRef = context.spawn(behavior, s"game-$matchId").unsafeUpcast[GameActor.GameCommand]

            subscribers.foreach { case (pid, ref) => actorRef ! bundle.actor.subscribeCommand(ref, pid) }

            persistActor ! PersistenceProtocol.SaveSnapshot(
              matchId,
              gameType,
              game.asInstanceOf[Game[_, _, _, _, _]],
              replyTo = context.system.ignoreRef
            )

            context.log.info(s"Created and persisted new match $matchId in room $roomId")
            publisher.publish(GameEvent.GameStarted(matchId, gameType, n))
            replyTo ! GameStarted(roomId)
            val updatedPlayerGames =
              players.foldLeft(playerGames)((idx, pid) => idx.updated(pid, idx.getOrElse(pid, Set.empty) + roomId))
            running(
              activeGames + (roomId -> (gameType, matchId, actorRef)),
              completedMatch,
              updatedPlayerGames,
              lobbyManager,
              playerManager,
              persistActor,
              gameRepo,
              moveRepo,
              chatRepo,
              publisher
            )
          }

        case GameCompleted(matchId, roomId, result, subscribers) =>
          activeGames.get(roomId) match {
            case Some((gameType, _, _)) =>
              context.log.info(s"Match $matchId in room $roomId completed with result $result — actor self-terminating")
              // hand the match's subscribers back to the room so it survives as a post-game room (chat + rematch)
              lobbyManager ! LobbyManager.MatchEnded(roomId, subscribers)
              // drop the finished game from every player's index, removing players left with no live games
              val prunedPlayerGames =
                playerGames.view.mapValues(_ - roomId).filter(_._2.nonEmpty).toMap
              running(
                activeGames - roomId,
                completedMatch + (roomId -> (matchId, gameType)),
                prunedPlayerGames,
                lobbyManager,
                playerManager,
                persistActor,
                gameRepo,
                moveRepo,
                chatRepo,
                publisher
              )
            case None =>
              context.log.warn(s"Received GameCompleted for unknown room: $roomId")
              Behaviors.same
          }

        case RunGameOperation(gameId, op, replyTo) =>
          activeGames.get(gameId) match {
            case Some((gameType, _, gameActor)) =>
              val adaptedRef: ActorRef[Either[GameError, GameState]] =
                context.messageAdapter(response => WrappedGameResponse(response, replyTo))

              GameRegistry.forType(gameType).module.toGameCommand(op, adaptedRef) match {
                case Right(cmd) => gameActor ! cmd
                case Left(err)  => replyTo ! ErrorResponse(err.message)
              }

            case None =>
              completedMatch.get(gameId) match {
                case Some((matchId, gameType)) =>
                  op match {
                    case GameOperation.GetState =>
                      context.pipeToSelf(gameRepo.loadGame(matchId, gameType).attempt.unsafeToFuture()) {
                        case Success(result) => CompletedGameLoaded(result, gameId, gameType, replyTo)
                        // $COVERAGE-OFF$ .attempt converts IO failures to Success(Left(ex)); Failure is unreachable
                        case Failure(ex) => CompletedGameLoaded(Left(ex), gameId, gameType, replyTo)
                        // $COVERAGE-ON$
                      }
                    case _ =>
                      replyTo ! MoveRejected("Game has already ended")
                  }
                case None =>
                  context.log.warn(s"No game found for room $gameId to forward operation $op")
                  replyTo ! GameNotFound(gameId)
              }
          }
          Behaviors.same

        case CompletedGameLoaded(result, gameId, gameType, replyTo) =>
          result match {
            case Right(Some(game)) =>
              replyTo ! GameStatus(GameRegistry.forType(gameType).serializeGame(game, None))
            case Right(None) =>
              context.log.warn(s"Completed game $gameId has no record in the database")
              replyTo ! GameNotFound(gameId)
            case Left(ex) =>
              context.log.error("Failed to load completed game state from DB", ex)
              replyTo ! ErrorResponse("Failed to retrieve game state")
          }
          Behaviors.same

        case GetMoveHistory(gameId, replyTo) =>
          // the move log is keyed by matchId. Resolve the room's live or most-recent match; if the id is not a known
          // room (e.g. a game finished before a restart, or a direct match lookup), fall back to using it as the match.
          val matchId = activeGames.get(gameId).map(_._2).orElse(completedMatch.get(gameId).map(_._1)).getOrElse(gameId)
          context.pipeToSelf(moveRepo.loadMoves(matchId).attempt.unsafeToFuture()) {
            case Success(result) => MoveHistoryLoaded(result, gameId, replyTo)
            // $COVERAGE-OFF$ .attempt converts IO failures to Success(Left(ex)); Failure is unreachable
            case Failure(ex) => MoveHistoryLoaded(Left(ex), gameId, replyTo)
            // $COVERAGE-ON$
          }
          Behaviors.same

        case MoveHistoryLoaded(result, gameId, replyTo) =>
          result match {
            case Right(moves) => replyTo ! MoveHistory(gameId, moves)
            case Left(ex)     =>
              context.log.error(s"Failed to load move history for $gameId", ex)
              replyTo ! ErrorResponse("Failed to retrieve move history")
          }
          Behaviors.same

        case GetChatHistory(gameId, replyTo) =>
          context.pipeToSelf(chatRepo.recent(gameId).attempt.unsafeToFuture()) {
            case Success(result) => ChatHistoryLoaded(result, gameId, replyTo)
            // $COVERAGE-OFF$ .attempt converts IO failures to Success(Left(ex)); Failure is unreachable
            case Failure(ex) => ChatHistoryLoaded(Left(ex), gameId, replyTo)
            // $COVERAGE-ON$
          }
          Behaviors.same

        case ChatHistoryLoaded(result, gameId, replyTo) =>
          result match {
            case Right(messages) => replyTo ! ChatHistory(gameId, messages)
            case Left(ex)        =>
              context.log.error(s"Failed to load chat history for $gameId", ex)
              replyTo ! ErrorResponse("Failed to retrieve chat history")
          }
          Behaviors.same

        case WrappedLobbiesListed(listed, replyTo) =>
          replyTo ! LobbiesListed(listed.lobbies, listed.page, listed.limit, listed.total)
          Behaviors.same

        case WrappedGameResponse(response, replyTo) =>
          response match {
            case Right(state) => replyTo ! GameStatus(state)
            case Left(error)  => replyTo ! MoveRejected(error.message)
          }
          Behaviors.same

        case WrappedForfeitResponse(response, gameId, replyTo) =>
          response match {
            case Right(state) => replyTo ! GameForfeited(gameId, state)
            case Left(error)  => replyTo ! MoveRejected(error.message)
          }
          Behaviors.same

        case RestoreGames(_, _) =>
          context.log.warn("Received RestoreGames while already in running state; ignoring.")
          Behaviors.same
      }
    }
}
