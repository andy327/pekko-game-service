package com.andy327.server.actors.core

import scala.util.Success

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameError, GameType, PlayerId}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.game.{GameModuleBundle, GameOperation, GameRegistry}
import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyMetadata, Player}

/**
 * A supervisor actor that handles creating and monitoring one child actor per game, provides an API for creating games
 * and forwarding arbitrary game-specific commands, and restores persisted games at start-up through the GameRepository.
 *
 * The GameManager keeps the message-handling behavior of the game service game-agnostic, by pattern-matching on the
 * GameType.
 */
object GameManager {
  sealed trait Command

  final case class CreateLobby(gameType: GameType, host: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class JoinLobby(gameId: String, player: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class LeaveLobby(gameId: String, player: Player, replyTo: ActorRef[GameResponse]) extends Command
  final case class StartGame(gameId: String, playerId: PlayerId, replyTo: ActorRef[GameResponse]) extends Command
  final case class ListLobbies(replyTo: ActorRef[GameResponse]) extends Command
  final case class GetLobbyInfo(gameId: String, replyTo: ActorRef[GameResponse]) extends Command
  final case class GameCompleted(gameId: String, result: GameLifecycleStatus.GameEnded) extends Command

  final case class RunGameOperation(gameId: String, op: GameOperation, replyTo: ActorRef[GameResponse]) extends Command
  final protected[core] case class RestoreGames(games: Map[String, (GameType, Game[_, _, _, _, _])]) extends Command
  final private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

  sealed trait GameResponse
  final case class LobbyCreated(gameId: String, host: Player) extends GameResponse
  final case class LobbyJoined(gameId: String, metadata: LobbyMetadata, joinedPlayer: Player) extends GameResponse
  final case class LobbyLeft(gameId: String, message: String) extends GameResponse
  final case class GameStarted(gameId: String) extends GameResponse
  final case class LobbiesListed(lobbies: List[LobbyMetadata]) extends GameResponse
  final case class LobbyInfo(metadata: LobbyMetadata) extends GameResponse
  final case class GameStatus(state: GameState) extends GameResponse
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

        // Load games from DB on startup asynchronously, (IO.defer(...).attempt catches all exceptions)
        context.pipeToSelf(IO.defer(gameRepo.loadAllGames()).attempt.unsafeToFuture()) {
          case Success(Right(games)) =>
            context.log.info(s"Restoring ${games.size} games from the database")
            RestoreGames(games)
          case Success(Left(ex)) =>
            context.log.error("Failed to load games from DB", ex)
            RestoreGames(Map.empty)
        }

        // Enter initialization state while awaiting game restoration
        initializing(persistActor, stash, onReady)
      }
    }

  /**
    * Initialization state: waits for RestoreGames message after async DB load.
    * Transitions to running state once restoration is complete.
    */
  private def initializing(
      persistActor: ActorRef[PersistenceProtocol.Command],
      stash: StashBuffer[Command],
      onReady: Option[ActorRef[Ready.type]]
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    message match {
      case RestoreGames(games) =>
        val restoredActors = games.flatMap { case (gameId, (gameType, game)) =>
          GameRegistry.forType(gameType) match {
            case Some(GameModuleBundle(_, gameActor)) =>
              val behavior = gameActor.fromSnapshot(gameId, game, persistActor, context.self)
              val actorRef = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]
              Some(gameId -> (gameType, actorRef))
            case None =>
              context.log.warn(s"No GameModule registered for type: $gameType — skipping restore for $gameId")
              None
          }
        }

        context.log.info(s"Initialized ${restoredActors.size} game actors from snapshots")

        // tell the listener we are ready
        onReady.foreach(_ ! Ready)

        // unstash everything and switch to running behavior (lobbies start fresh)
        stash.unstashAll(running(Map.empty, restoredActors, persistActor))

      case other =>
        stash.stash(other)
        Behaviors.same
    }
  }

  /**
    * Running state: main loop handling live operations.
    * Accepts new game creation and forwards commands to existing game actors.
    */
  private def running(
      lobbies: Map[String, LobbyMetadata],
      games: Map[String, (GameType, ActorRef[GameActor.GameCommand])],
      persistActor: ActorRef[PersistenceProtocol.Command]
  ): Behavior[Command] =
    Behaviors.setup { implicit context =>
      Behaviors.receiveMessage {
        case CreateLobby(gameType, host, replyTo) =>
          context.log.info(s"Creating new lobby for game type $gameType with host ${host.name}")
          val lobby = LobbyMetadata.newLobby(gameType, host)
          replyTo ! LobbyCreated(lobby.gameId, host)
          running(lobbies + (lobby.gameId -> lobby), games, persistActor)

        case JoinLobby(gameId, player, replyTo) =>
          lobbies.get(gameId) match {
            case Some(metadata) if metadata.status.isJoinable =>
              if (metadata.players.contains(player.id)) {
                replyTo ! ErrorResponse("Player already in game")
                Behaviors.same
              } else if (metadata.players.size >= metadata.gameType.maxPlayers) {
                replyTo ! ErrorResponse("Cannot join - lobby is full")
                Behaviors.same
              } else {
                val updatedPlayers = metadata.players + (player.id -> player)
                val updatedStatus =
                  if (updatedPlayers.size >= metadata.gameType.minPlayers) GameLifecycleStatus.ReadyToStart
                  else GameLifecycleStatus.WaitingForPlayers

                val updatedMetadata = metadata.copy(players = updatedPlayers, status = updatedStatus)
                replyTo ! LobbyJoined(gameId, updatedMetadata, player)
                running(lobbies + (gameId -> updatedMetadata), games, persistActor)
              }

            case Some(_) =>
              replyTo ! ErrorResponse("Cannot join — game already started or ended")
              Behaviors.same

            case None =>
              replyTo ! ErrorResponse("No such lobby")
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

              if (player.id == metadata.hostId) {
                val cancelled = newMetadata.copy(status = GameLifecycleStatus.Cancelled)
                context.log.info(s"Host ($player.name) left lobby $gameId. Cancelling game...")
                replyTo ! LobbyLeft(gameId, s"Lobby $gameId ended - host left")
                running(lobbies + (gameId -> cancelled), games, persistActor)
              } else {
                context.log.info(s"Player $player.name left lobby $gameId")
                replyTo ! LobbyLeft(gameId, s"$player.name left lobby $gameId")
                running(lobbies + (gameId -> newMetadata), games, persistActor)
              }

            case None =>
              replyTo ! ErrorResponse("No such lobby")
              Behaviors.same
          }

        case StartGame(gameId, playerId, replyTo) =>
          lobbies.get(gameId) match {
            case Some(metadata) if metadata.hostId == playerId && metadata.status == GameLifecycleStatus.ReadyToStart =>
              GameRegistry.forType(metadata.gameType) match {
                case Some(GameModuleBundle(_, gameActor)) =>
                  val players = metadata.players.keySet.toSeq
                  val (game, behavior) = gameActor.create(gameId, players, persistActor, context.self)

                  val actorRef = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]

                  // Persist immediately after creation; no need to wait for acknowledgement
                  persistActor ! PersistenceProtocol.SaveSnapshot(
                    gameId,
                    metadata.gameType,
                    game.asInstanceOf[Game[_, _, _, _, _]],
                    replyTo = context.system.ignoreRef
                  )

                  context.log.info(s"Created and persisted new game with gameId: $gameId")
                  replyTo ! GameStarted(gameId)

                  running(
                    lobbies + (gameId -> metadata.copy(status = GameLifecycleStatus.InProgress)),
                    games + (gameId -> ((metadata.gameType, actorRef))),
                    persistActor
                  )

                case None =>
                  context.log.warn(s"No GameModule registered for type: ${metadata.gameType}")
                  replyTo ! ErrorResponse(s"Unsupported game type: ${metadata.gameType}")
                  Behaviors.same
              }

            case Some(_) =>
              replyTo ! ErrorResponse("Only host can start, and game must be ready to start")
              Behaviors.same

            case None =>
              replyTo ! ErrorResponse("No such game")
              Behaviors.same
          }

        case ListLobbies(replyTo) =>
          context.log.info("Listing available lobbies")
          val activeLobbies = lobbies.values.filter(_.status.isJoinable).toList
          replyTo ! LobbiesListed(activeLobbies)
          Behaviors.same

        case GetLobbyInfo(gameId, replyTo) =>
          context.log.info(s"Getting lobby metadata for game $gameId")
          lobbies.get(gameId) match {
            case Some(metadata) => replyTo ! LobbyInfo(metadata)
            case None           => replyTo ! ErrorResponse(s"No game with gameId: $gameId")
          }
          Behaviors.same

        case GameCompleted(gameId, result) =>
          lobbies.get(gameId) match {
            case Some(metadata) =>
              val updatedMetadata = metadata.copy(status = result)
              context.log.info(s"Marking game $gameId as completed with result $result")

              // TODO: Find a way to spin down actors, but still make their game state available for /status lookups

              // Stop the game actor if it exists
              // games.get(gameId).foreach(context.stop)

              // Remove the game actor reference
              // val updatedGames = games - gameId

              running(lobbies + (gameId -> updatedMetadata), games, persistActor)

            case None =>
              context.log.warn(s"Tried to complete unknown game: $gameId")
              Behaviors.same
          }

        case RunGameOperation(gameId, op, replyTo) =>
          games.get(gameId) match {
            case Some((gameType, gameActor)) =>
              val adaptedRef: ActorRef[Either[GameError, GameState]] =
                context.messageAdapter(response => WrappedGameResponse(response, replyTo))

              GameRegistry.forType(gameType) match {
                case Some(GameModuleBundle(module, _)) =>
                  module.toGameCommand(op, adaptedRef) match {
                    case Right(cmd) => gameActor ! cmd
                    case Left(err)  => replyTo ! ErrorResponse(err.message)
                  }

                case None =>
                  context.log.warn(s"No GameModule registered for type: $gameType")
                  replyTo ! ErrorResponse(s"Unsupported game type: $gameType")
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
