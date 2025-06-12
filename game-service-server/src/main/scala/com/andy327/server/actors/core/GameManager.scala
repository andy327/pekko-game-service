package com.andy327.server.actors.core

import java.util.UUID

import scala.util.Success

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, StashBuffer}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.{GameError, TicTacToe}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.http.json.GameState

/**
 * A supervisor actor that handles creating and monitoring one child actor per game, provides an API for creating games
 * and forwarding arbitrary game-specific commands, and restores persisted games at start-up through the GameRepository.
 *
 * The GameManager keeps the message-handling behavior of the game service game-agnostic, by pattern-matching on the
 * GameType.
 */
object GameManager {
  sealed trait Command
  case class CreateGame(gameType: GameType, players: Seq[String], replyTo: ActorRef[GameResponse]) extends Command
  case class ForwardToGame[T](gameId: String, message: T, replyTo: Option[ActorRef[GameResponse]]) extends Command
  protected[core] case class RestoreGames(games: Map[String, (GameType, Game[_, _, _, _, _])]) extends Command
  private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

  sealed trait GameResponse
  case class GameCreated(gameId: String) extends GameResponse
  case class GameStatus(state: GameState) extends GameResponse
  case class ErrorResponse(message: String) extends GameResponse

  /** Emitted exactly once when the DB restore is complete. */
  case object Ready

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
        val restoredActors = games.map { case (gameId, (gameType, game)) =>
          val gameActor = gameType match {
            case GameType.TicTacToe =>
              context.spawn(
                TicTacToeActor.fromSnapshot(gameId, game.asInstanceOf[TicTacToe], persistActor),
                s"game-$gameId"
              ).unsafeUpcast[GameActor.GameCommand]
          }
          gameId -> gameActor
        }
        context.log.info(s"Initialized ${games.size} game actors from snapshots")

        // tell the listener we are ready
        onReady.foreach(_ ! Ready)

        // unstash everything and switch to running behavior
        stash.unstashAll(running(restoredActors, persistActor))

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
      games: Map[String, ActorRef[GameActor.GameCommand]],
      persistActor: ActorRef[PersistenceProtocol.Command]
  ): Behavior[Command] =
    Behaviors.setup { implicit context =>
      Behaviors.receiveMessage {
        case CreateGame(gameType, players, replyTo) =>
          if (players.size != 2) {
            replyTo ! ErrorResponse(s"Expected 2 players, got ${players.size}")
            Behaviors.same
          } else {
            val gameId = UUID.randomUUID().toString
            val (game, actor) = gameType match {
              case GameType.TicTacToe =>
                val (game, behavior) = TicTacToeActor.create(gameId, players, persistActor)
                val actor = context.spawn(behavior, s"game-$gameId").unsafeUpcast[GameActor.GameCommand]
                (game, actor)
            }

            // Persist immediately after creation; no need to wait for acknowledgement
            persistActor ! PersistenceProtocol.SaveSnapshot(gameId, gameType, game, replyTo = context.system.ignoreRef)

            context.log.info(s"Created and persisted new game with gameId: $gameId")
            replyTo ! GameCreated(gameId)

            running(games + (gameId -> actor), persistActor)
          }

        case ForwardToGame(gameId, msg, replyToOpt) =>
          games.get(gameId) match {
            case Some(gameActor) =>
              replyToOpt.foreach { replyTo =>
                // TODO: push this reply adapter up to the caller and remove specific message-handling from here
                val adaptedRef: ActorRef[Either[GameError, GameState]] =
                  context.messageAdapter { response =>
                    WrappedGameResponse(response, replyTo)
                  }

                val actualCommand = msg match {
                  case TicTacToeActor.MakeMove(p, l, _) =>
                    TicTacToeActor.MakeMove(p, l, adaptedRef)
                  case TicTacToeActor.GetState(_) =>
                    TicTacToeActor.GetState(adaptedRef)
                }

                gameActor ! actualCommand
              }

            case None =>
              context.log.warn(s"No game found with gameId $gameId to forward message $msg")
              replyToOpt.foreach(_ ! ErrorResponse(s"No game found with gameId $gameId"))
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
