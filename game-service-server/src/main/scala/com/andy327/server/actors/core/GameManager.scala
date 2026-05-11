package com.andy327.server.actors.core

import scala.util.Success

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

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
 * completion. Lobby lifecycle state is delegated to a LobbyManager child actor.
 *
 * On startup, GameManager asynchronously restores persisted games from the database, stashing incoming messages until
 * restoration is complete.
 */
object GameManager {
  sealed trait Command

  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class JoinLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class LeaveLobby(gameId: GameId, player: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class StartGame(gameId: GameId, playerId: PlayerId, replyTo: ActorRef[GameResponse]) extends Command
  final case class ListLobbies(replyTo: ActorRef[GameResponse]) extends Command
  final case class GetLobbyInfo(gameId: GameId, replyTo: ActorRef[GameResponse]) extends Command
  final case class GameCompleted(gameId: GameId, result: GameLifecycleStatus.GameEnded) extends Command
  final case class RunGameOperation(gameId: GameId, op: GameOperation, replyTo: ActorRef[GameResponse]) extends Command

  final protected[core] case class RestoreGames(games: Map[GameId, (GameType, Game[_, _, _, _, _])]) extends Command

  /** Sent by LobbyManager after validating a StartGame request; GameManager spawns the child actor. */
  final private[core] case class SpawnGame(
      gameId: GameId,
      gameType: GameType,
      players: Set[PlayerId],
      replyTo: ActorRef[GameResponse]
  ) extends Command

  final private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

  sealed trait GameResponse
  final case class LobbyCreated(gameId: GameId, host: Player) extends GameResponse
  final case class LobbyJoined(gameId: GameId, metadata: LobbyMetadata, joinedPlayer: Player) extends GameResponse
  final case class LobbyLeft(gameId: GameId, message: String) extends GameResponse
  final case class GameStarted(gameId: GameId) extends GameResponse
  final case class LobbiesListed(lobbies: List[LobbyMetadata]) extends GameResponse
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

        context.pipeToSelf(IO.defer(gameRepo.loadAllGames()).attempt.unsafeToFuture()) {
          case Success(Right(games)) =>
            context.log.info(s"Restoring ${games.size} games from the database")
            RestoreGames(games)
          case Success(Left(ex)) =>
            context.log.error("Failed to load games from DB", ex)
            RestoreGames(Map.empty)
        }

        initializing(lobbyManager, persistActor, stash, onReady)
      }
    }

  /**
    * Initialization state: waits for RestoreGames message after async DB load.
    * Transitions to running state once restoration is complete.
    */
  private def initializing(
      lobbyManager: ActorRef[LobbyManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command],
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
        stash.unstashAll(running(restoredActors, lobbyManager, persistActor))

      case other =>
        stash.stash(other)
        Behaviors.same
    }
  }

  /**
    * Running state: routes lobby commands to LobbyManager and handles game actor lifecycle.
    */
  private def running(
      games: Map[GameId, (GameType, ActorRef[GameActor.GameCommand])],
      lobbyManager: ActorRef[LobbyManager.Command],
      persistActor: ActorRef[PersistenceProtocol.Command]
  ): Behavior[Command] =
    Behaviors.setup { implicit context =>
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

        case ListLobbies(replyTo) =>
          lobbyManager ! LobbyManager.ListLobbies(replyTo)
          Behaviors.same

        case GetLobbyInfo(gameId, replyTo) =>
          lobbyManager ! LobbyManager.GetLobbyInfo(gameId, replyTo)
          Behaviors.same

        case SpawnGame(gameId, gameType, players, replyTo) =>
          val gameActor = GameRegistry.forType(gameType).actor
          val (game, behavior) = gameActor.create(gameId, players.toSeq, persistActor, context.self)
          val actorRef = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]

          persistActor ! PersistenceProtocol.SaveSnapshot(
            gameId,
            gameType,
            game.asInstanceOf[Game[_, _, _, _, _]],
            replyTo = context.system.ignoreRef
          )

          context.log.info(s"Created and persisted new game with gameId: $gameId")
          replyTo ! GameStarted(gameId)
          running(games + (gameId -> (gameType, actorRef)), lobbyManager, persistActor)

        case GameCompleted(gameId, result) =>
          if (games.contains(gameId)) {
            context.log.info(s"Marking game $gameId as completed with result $result")
            lobbyManager ! LobbyManager.MarkCompleted(gameId, result)
          } else {
            context.log.warn(s"Tried to complete unknown game: $gameId")
          }
          Behaviors.same

        case RunGameOperation(gameId, op, replyTo) =>
          games.get(gameId) match {
            case Some((gameType, gameActor)) =>
              val adaptedRef: ActorRef[Either[GameError, GameState]] =
                context.messageAdapter(response => WrappedGameResponse(response, replyTo))

              GameRegistry.forType(gameType).module.toGameCommand(op, adaptedRef) match {
                case Right(cmd) => gameActor ! cmd
                case Left(err)  => replyTo ! ErrorResponse(err.message)
              }

            case None =>
              context.log.warn(s"No game found with gameId $gameId to forward operation $op")
              replyTo ! ErrorResponse(s"No game found with gameId $gameId")
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
