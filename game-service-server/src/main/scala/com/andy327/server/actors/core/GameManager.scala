package com.andy327.server.actors.core

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.http.scaladsl.model.ws.Message

import com.andy327.model.core.{Game, GameError, GameId, GameType, PlayerId}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.game.{GameOperation, GameRegistry}
import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyError, LobbyMetadata, Player}

/**
 * A supervisor actor responsible for game actor lifecycle and request routing.
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
 *   - Sends to: [[LobbyManager]] (lobby commands), [[PlayerManager]] (player session commands), game actors
 *     (game operations via [[GameActor.GameCommand]]), [[com.andy327.server.actors.persistence.PersistenceProtocol]]
 *     (`SaveSnapshot` on game start)
 */
object GameManager {
  sealed trait Command

  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class JoinLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class StartGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse]) extends Command
  final case class ListLobbies(
      gameType: Option[GameType],
      page: Int,
      limit: Int,
      replyTo: ActorRef[GameResponse]
  ) extends Command
  final case class GetLobbyInfo(gameId: GameId, replyTo: ActorRef[GameResponse]) extends Command
  final case class GameCompleted(gameId: GameId, result: GameLifecycleStatus.GameEnded) extends Command
  final case class RunGameOperation(gameId: GameId, op: GameOperation, replyTo: ActorRef[GameResponse]) extends Command
  final case class SubscribeToLobby(gameId: GameId, playerRef: ActorRef[PlayerActor.Command]) extends Command
  final case class SubscribeToGame(gameId: GameId, playerRef: ActorRef[PlayerActor.Command]) extends Command
  final case class RegisterPlayer(
      player: Player,
      wsOut: ActorRef[Message],
      replyTo: ActorRef[ActorRef[PlayerActor.Command]]
  ) extends Command
  final case class PlayerDisconnected(playerId: PlayerId) extends Command

  final protected[core] case class RestoreGames(games: Map[GameId, (GameType, Game[_, _, _, _, _])]) extends Command

  /** Sent by LobbyManager after validating a StartGame request; GameManager spawns the child actor. */
  final private[core] case class SpawnGame(
      gameId: GameId,
      gameType: GameType,
      players: Set[PlayerId],
      replyTo: ActorRef[GameResponse],
      subscribers: Set[ActorRef[PlayerActor.Command]] = Set.empty
  ) extends Command

  final private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

  /** Result of a DB fallback load for a completed game's state. */
  final private case class CompletedGameLoaded(
      result: Either[Throwable, Option[Game[_, _, _, _, _]]],
      gameType: GameType,
      replyTo: ActorRef[GameResponse]
  ) extends Command

  sealed trait GameResponse
  final case class LobbyCreated(gameId: GameId, host: Player) extends GameResponse
  final case class LobbyJoined(gameId: GameId, metadata: LobbyMetadata, joinedPlayer: Player) extends GameResponse
  final case class LobbyLeft(gameId: GameId, message: String) extends GameResponse
  final case class GameStarted(gameId: GameId) extends GameResponse
  final case class LobbiesListed(lobbies: List[LobbyMetadata], page: Int, limit: Int, total: Int) extends GameResponse
  final case class LobbyInfo(metadata: LobbyMetadata) extends GameResponse
  final case class GameStatus(state: GameState) extends GameResponse
  final case class LobbyErrorResponse(error: LobbyError) extends GameResponse
  final case class ErrorResponse(message: String) extends GameResponse

  /** Emitted once when the DB restore is complete. */
  case object Ready extends GameResponse

  /** Factory used from GameServer */
  @annotation.nowarn("msg=match may not be exhaustive")
  def apply(
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      onReady: Option[ActorRef[Ready.type]] = None
  ): Behavior[Command] =
    Behaviors.withStash(capacity = 128) { stash =>
      Behaviors.setup { context =>
        implicit val runtime: IORuntime = IORuntime.global

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

  /**
    * Initialization state: waits for RestoreGames message after async DB load.
    * Transitions to running state once restoration is complete.
    */
  private def initializing(
      lobbyManager: ActorRef[LobbyManager.Command],
      playerManager: ActorRef[PlayerManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository,
      stash: StashBuffer[Command],
      onReady: Option[ActorRef[Ready.type]]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
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
        stash.unstashAll(running(restoredActors, Map.empty, lobbyManager, playerManager, persistActor, gameRepo))

      case other =>
        stash.stash(other)
        Behaviors.same
    }
  }

  /**
    * Running state: routes lobby commands to LobbyManager and handles game actor lifecycle.
    *
    * Active games are tracked in activeGames. When a game completes its actor is stopped and its
    * game type is retained in completedGameTypes so that subsequent status queries can fall back to
    * the database.
    */
  private def running(
      activeGames: Map[GameId, (GameType, ActorRef[GameActor.GameCommand])],
      completedGameTypes: Map[GameId, GameType],
      lobbyManager: ActorRef[LobbyManager.Command],
      playerManager: ActorRef[PlayerManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
      gameRepo: GameRepository
  ): Behavior[Command] =
    Behaviors.setup { implicit context =>
      implicit val runtime: IORuntime = IORuntime.global

      Behaviors.receiveMessage {
        case CreateLobby(gameType, host, replyTo) =>
          lobbyManager ! LobbyManager.CreateLobby(gameType, host, replyTo)
          Behaviors.same

        case JoinLobby(gameId, player, replyTo) =>
          lobbyManager ! LobbyManager.JoinLobby(gameId, player, replyTo)
          Behaviors.same

        case LeaveLobby(gameId, player, replyTo) =>
          lobbyManager ! LobbyManager.LeaveLobby(gameId, player, replyTo)
          Behaviors.same

        case StartGame(gameId, playerId, replyTo) =>
          lobbyManager ! LobbyManager.StartGame(gameId, playerId, replyTo)
          Behaviors.same

        case ListLobbies(gameType, page, limit, replyTo) =>
          lobbyManager ! LobbyManager.ListLobbies(gameType, page, limit, replyTo)
          Behaviors.same

        case GetLobbyInfo(gameId, replyTo) =>
          lobbyManager ! LobbyManager.GetLobbyInfo(gameId, replyTo)
          Behaviors.same

        case SubscribeToLobby(gameId, playerRef) =>
          lobbyManager ! LobbyManager.SubscribeToLobby(gameId, playerRef)
          Behaviors.same

        case SubscribeToGame(gameId, playerRef) =>
          activeGames.get(gameId) match {
            case Some((gameType, gameActor)) =>
              gameActor ! GameRegistry.forType(gameType).module.subscribeCommand(playerRef)
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
          val gameActor = GameRegistry.forType(gameType).actor
          val (game, behavior) = gameActor.create(gameId, players.toSeq, persistActor, context.self)
          val actorRef = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]

          val module = GameRegistry.forType(gameType).module
          subscribers.foreach(ref => actorRef ! module.subscribeCommand(ref))

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
              replyTo ! GameStatus(GameRegistry.forType(gameType).module.serialize(game))
            case Right(None) =>
              replyTo ! ErrorResponse("Game state not found in database")
            case Left(ex) =>
              context.log.error("Failed to load completed game state from DB", ex)
              replyTo ! ErrorResponse("Failed to retrieve game state")
          }
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
