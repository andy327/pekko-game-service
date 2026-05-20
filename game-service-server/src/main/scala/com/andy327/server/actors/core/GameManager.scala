package com.andy327.server.actors.core

import scala.concurrent.duration._
import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.util.Timeout

import com.andy327.model.core.{Game, GameError, GameId, GameType, PlayerId}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.game.{GameOperation, GameRegistry}
import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, Player}

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
  *   - Parent: root (top-level, created by [[com.andy327.server.GameServer]])
  *   - Children: [[LobbyManager]], [[PlayerManager]], one game actor per active game
  *   - Receives from: HTTP route handlers (all public `Command` messages)
  *   - Sends to: [[LobbyManager]] (lobby commands), [[PlayerManager]] (player session commands), game actors (game
  *     operations via [[GameActor.GameCommand]]), [[com.andy327.server.actors.persistence.PersistenceProtocol]]
  *     (`SaveSnapshot` on game start)
  */
object GameManager {
  sealed trait Command

  // --- Lobby commands (forwarded verbatim to LobbyManager) ---

  /** Create a new lobby; replies with [[LobbyCreated]]. */
  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameResponse]) extends Command

  /** Join an existing lobby; replies with [[LobbyJoined]] or a [[LobbyErrorResponse]]. */
  final case class JoinLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command

  /** Leave a lobby; cancels the lobby if the departing player is the host. */
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command

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

  /** Subscribe `playerRef` to lobby push events via LobbyManager. */
  final case class SubscribeToLobby(gameId: GameId, playerId: PlayerId, playerRef: ActorRef[PlayerActor.Command])
      extends Command

  // --- Game operation commands (routed to a specific game actor via GameRegistry) ---

  /** Forward `op` to the game actor for `gameId`; replies with [[GameStatus]] or [[ErrorResponse]]. */
  final case class RunGameOperation(gameId: GameId, op: GameOperation, replyTo: ActorRef[GameResponse]) extends Command

  /** Subscribe `playerRef` to game-state push events from the game actor for `gameId`. */
  final case class SubscribeToGame(gameId: GameId, playerRef: ActorRef[PlayerActor.Command]) extends Command

  // --- Player session commands (forwarded to PlayerManager) ---

  /** Register (or reconnect) a player; replies with the spawned [[PlayerActor]] ref. */
  final case class RegisterPlayer(
      player: Player,
      wsOut: ActorRef[Message],
      replyTo: ActorRef[ActorRef[PlayerActor.Command]]
  ) extends Command

  /** Clean up the PlayerActor and session state for a disconnected player. */
  final case class PlayerDisconnected(playerId: PlayerId) extends Command

  // --- Lifecycle commands ---

  /** Sent by a game actor when its game reaches a terminal state (won or draw). */
  final case class GameCompleted(gameId: GameId, result: GameLifecycleStatus.GameEnded) extends Command

  // --- Internal commands (not reachable from HTTP) ---

  /** Carries the result of the async DB restore initiated at startup; transitions from initializing to running. */
  final protected[core] case class RestoreGames(games: Map[GameId, (GameType, Game[_, _, _, _, _])]) extends Command

  /** Sent by LobbyManager after validating a StartGame request; GameManager spawns the child actor. */
  final private[core] case class SpawnGame(
      gameId: GameId,
      gameType: GameType,
      players: Set[PlayerId],
      replyTo: ActorRef[GameResponse],
      subscribers: Set[ActorRef[PlayerActor.Command]] = Set.empty
  ) extends Command

  /** Adapter wrapper — converts a game actor's `Either[GameError, GameState]` reply into a [[GameResponse]]. */
  final private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

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

  /** Carries the result of a [[PlayerManager.LookupPlayer]] ask initiated during auto-subscribe on lobby create/join. */
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
      gameType: GameType,
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

  // Game responses
  final case class GameStarted(gameId: GameId) extends GameResponse
  final case class GameStatus(state: GameState) extends GameResponse

  // Error responses
  final case class LobbyErrorResponse(error: LobbyError) extends GameResponse
  final case class ErrorResponse(message: String) extends GameResponse

  /** Emitted once when the DB restore is complete; used in tests to await the running state. */
  case object Ready extends GameResponse

  /** Timeout for internal `context.ask` calls used to look up player refs during lobby auto-subscribe. */
  private val subscribeAskTimeout: Timeout = Timeout(3.seconds)

  /** Create the GameManager, kick off an async DB restore, and stash messages until restoration completes.
    *
    * @param persistActor shared actor for all persistence I/O
    * @param gameRepo the repository used for the initial game restore and completed-game state lookups
    * @param onReady optional ref that receives a [[Ready]] signal once the running state is entered (used in tests)
    */
  @annotation.nowarn("msg=match may not be exhaustive")
  def apply(
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      onReady: Option[ActorRef[Ready.type]] = None
  )(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.withStash(capacity = 128) { stash =>
      Behaviors.setup { context =>
        val lobbyManager = context.spawn(LobbyManager(context.self), "lobby-manager")
        val playerManager = context.spawn(PlayerManager(), "player-manager")

        context.pipeToSelf(IO.defer(gameRepo.loadAllGames()).attempt.unsafeToFuture()) {
          case Success(Right(games)) =>
            context.log.info(s"Restoring ${games.size} games from the database")
            RestoreGames(games)
          case Success(Left(ex)) =>
            context.log.error("Failed to load games from DB", ex)
            RestoreGames(Map.empty)
        }

        initializing(lobbyManager, playerManager, persistActor, gameRepo, stash, onReady)
      }
    }

  /** Startup behavior: stashes all incoming messages until the async DB restore completes.
    *
    * Waits for a single [[RestoreGames]] message, spawns actors for every recovered game, then unstashes
    * all buffered messages and transitions to [[running]].
    */
  private def initializing(
      lobbyManager: ActorRef[LobbyManager.Command],
      playerManager: ActorRef[PlayerManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      stash: StashBuffer[Command],
      onReady: Option[ActorRef[Ready.type]]
  )(implicit runtime: IORuntime): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case RestoreGames(games) =>
        val restoredActors = games.map { case (gameId, (gameType, game)) =>
          val gameActor = GameRegistry.forType(gameType).actor
          val behavior = gameActor.fromSnapshot(gameId, game, persistActor, context.self)
          val actorRef = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]
          gameId -> (gameType, actorRef)
        }

        context.log.info(s"Initialized ${restoredActors.size} game actors from snapshots")
        onReady.foreach(_ ! Ready)
        stash.unstashAll(
          running(restoredActors, Map.empty, lobbyManager, playerManager, persistActor, gameRepo)(runtime)
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
    * @param activeGames map from GameId to (GameType, game actor ref) for all currently running games
    * @param completedGameTypes retains the GameType of finished games so GetState queries can fall back to the DB
    */
  private def running(
      activeGames: Map[GameId, (GameType, ActorRef[GameActor.GameCommand])],
      completedGameTypes: Map[GameId, GameType],
      lobbyManager: ActorRef[LobbyManager.Command],
      playerManager: ActorRef[PlayerManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository
  )(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.setup { implicit context =>
      Behaviors.receiveMessage {
        case CreateLobby(gameType, host, replyTo) =>
          val adapter = context.messageAdapter[GameResponse](LobbyResponseIntercepted(_, host.id, replyTo))
          lobbyManager ! LobbyManager.CreateLobby(gameType, host, adapter)
          Behaviors.same

        case JoinLobby(gameId, player, replyTo) =>
          val adapter = context.messageAdapter[GameResponse](LobbyResponseIntercepted(_, player.id, replyTo))
          lobbyManager ! LobbyManager.JoinLobby(gameId, player, adapter)
          Behaviors.same

        case LeaveLobby(gameId, player, replyTo) =>
          lobbyManager ! LobbyManager.LeaveLobby(gameId, player, replyTo)
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
              implicit val t: Timeout = subscribeAskTimeout
              context.ask(playerManager, PlayerManager.LookupPlayer(playerId, _)) {
                case Success(ref) => PlayerRefForSubscribe(ref, gameId, playerId, response, replyTo)
                case Failure(_)   => PlayerRefForSubscribe(None, gameId, playerId, response, replyTo)
              }
            case None =>
              replyTo ! response
          }
          Behaviors.same

        case PlayerRefForSubscribe(playerRefOpt, gameId, playerId, response, replyTo) =>
          playerRefOpt.foreach(ref => lobbyManager ! LobbyManager.SubscribeToLobby(gameId, playerId, ref))
          replyTo ! response
          Behaviors.same

        case SubscribeToLobby(gameId, playerId, playerRef) =>
          lobbyManager ! LobbyManager.SubscribeToLobby(gameId, playerId, playerRef)
          Behaviors.same

        case SubscribeToGame(gameId, playerRef) =>
          activeGames.get(gameId) match {
            case Some((gameType, gameActor)) =>
              gameActor ! GameRegistry.forType(gameType).actor.subscribeCommand(playerRef)
            case None =>
              context.log.warn(s"SubscribeToGame: no active game found for $gameId")
          }
          Behaviors.same

        case RegisterPlayer(player, wsOut, replyTo) =>
          playerManager ! PlayerManager.RegisterPlayer(player, wsOut, replyTo)
          Behaviors.same

        case PlayerDisconnected(playerId) =>
          playerManager ! PlayerManager.PlayerDisconnected(playerId)
          Behaviors.same

        case SpawnGame(gameId, gameType, players, replyTo, subscribers) =>
          val n = players.size
          if (n < gameType.minPlayers || n > gameType.maxPlayers) {
            val expected =
              if (gameType.minPlayers == gameType.maxPlayers) s"${gameType.minPlayers}"
              else s"${gameType.minPlayers}–${gameType.maxPlayers}"
            context.log.error(s"SpawnGame rejected for $gameId: $n players supplied, expected $expected")
            replyTo ! ErrorResponse(s"$expected players required, got $n")
            Behaviors.same
          } else {
            val bundle = GameRegistry.forType(gameType)
            val (game, behavior) = bundle.actor.create(gameId, players.toSeq, persistActor, context.self)
            val actorRef = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]

            subscribers.foreach(ref => actorRef ! bundle.actor.subscribeCommand(ref))

            persistActor ! PersistenceProtocol.SaveSnapshot(
              gameId,
              gameType,
              game.asInstanceOf[Game[_, _, _, _, _]],
              replyTo = context.system.ignoreRef
            )

            context.log.info(s"Created and persisted new game with gameId: $gameId")
            replyTo ! GameStarted(gameId)
            running(
              activeGames + (gameId -> (gameType, actorRef)),
              completedGameTypes,
              lobbyManager,
              playerManager,
              persistActor,
              gameRepo
            )
          }

        case GameCompleted(gameId, result) =>
          activeGames.get(gameId) match {
            case Some((gameType, gameActor)) =>
              context.log.info(s"Game $gameId completed with result $result — stopping actor")
              lobbyManager ! LobbyManager.MarkCompleted(gameId, result)
              context.stop(gameActor)
              running(
                activeGames - gameId,
                completedGameTypes + (gameId -> gameType),
                lobbyManager,
                playerManager,
                persistActor,
                gameRepo
              )
            case None =>
              context.log.warn(s"Received GameCompleted for unknown game: $gameId")
              Behaviors.same
          }

        case RunGameOperation(gameId, op, replyTo) =>
          activeGames.get(gameId) match {
            case Some((gameType, gameActor)) =>
              val adaptedRef: ActorRef[Either[GameError, GameState]] =
                context.messageAdapter(response => WrappedGameResponse(response, replyTo))

              GameRegistry.forType(gameType).module.toGameCommand(op, adaptedRef) match {
                case Right(cmd) => gameActor ! cmd
                case Left(err)  => replyTo ! ErrorResponse(err.message)
              }

            case None =>
              completedGameTypes.get(gameId) match {
                case Some(gameType) =>
                  op match {
                    case GameOperation.GetState =>
                      context.pipeToSelf(gameRepo.loadGame(gameId, gameType).attempt.unsafeToFuture()) {
                        case Success(result) => CompletedGameLoaded(result, gameType, replyTo)
                        // $COVERAGE-OFF$ .attempt converts IO failures to Success(Left(ex)); Failure is unreachable
                        case Failure(ex) => CompletedGameLoaded(Left(ex), gameType, replyTo)
                        // $COVERAGE-ON$
                      }
                    case _ =>
                      replyTo ! ErrorResponse("Game has already ended")
                  }
                case None =>
                  context.log.warn(s"No game found with gameId $gameId to forward operation $op")
                  replyTo ! ErrorResponse(s"No game found with gameId $gameId")
              }
          }
          Behaviors.same

        case CompletedGameLoaded(result, gameType, replyTo) =>
          result match {
            case Right(Some(game)) =>
              replyTo ! GameStatus(GameRegistry.forType(gameType).serializeGame(game))
            case Right(None) =>
              replyTo ! ErrorResponse("Game state not found in database")
            case Left(ex) =>
              context.log.error("Failed to load completed game state from DB", ex)
              replyTo ! ErrorResponse("Failed to retrieve game state")
          }
          Behaviors.same

        case WrappedLobbiesListed(listed, replyTo) =>
          replyTo ! LobbiesListed(listed.lobbies, listed.page, listed.limit, listed.total)
          Behaviors.same

        case WrappedGameResponse(response, replyTo) =>
          response match {
            case Right(state) => replyTo ! GameStatus(state)
            case Left(error)  => replyTo ! ErrorResponse(error.message)
          }
          Behaviors.same

        case RestoreGames(_) =>
          context.log.warn("Received RestoreGames while already in running state; ignoring.")
          Behaviors.same
      }
    }
}
