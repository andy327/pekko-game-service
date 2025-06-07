package com.andy327.server.actors.core

import java.util.UUID

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.{GameError, TicTacToe}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.http.json.GameState

object GameManager {
  sealed trait Command
  case class CreateGame(gameType: GameType, players: Seq[String], replyTo: ActorRef[String]) extends Command
  case class ForwardToGame[T](gameId: String, message: T, replyTo: Option[ActorRef[GameResponse]]) extends Command
  private case class RestoreGames(games: Map[String, (GameType, Game[_, _, _, _, _])]) extends Command
  private case class WrappedGameResponse(response: Either[GameError, GameState], replyTo: ActorRef[GameResponse])
      extends Command

  sealed trait GameResponse
  case class GameStatus(state: GameState) extends GameResponse
  case class ErrorResponse(message: String) extends GameResponse

  /** Factory used from GameServer */
  def apply(persistActor: ActorRef[PersistenceProtocol.Command], gameRepo: GameRepository): Behavior[Command] =
    Behaviors.setup { context =>
      implicit val runtime: IORuntime = IORuntime.global

      // Load games from DB on startup asynchronously
      context.pipeToSelf(IO.defer(gameRepo.loadAllGames()).attempt.unsafeToFuture()) {
        case Success(Right(games)) =>
          context.log.info(s"Restoring ${games.size} games from the database")
          RestoreGames(games)
        case Success(Left(ex)) =>
          context.log.error("Failed to load games from DB", ex)
          RestoreGames(Map.empty)
        case Failure(ex) =>
          context.log.error("Unexpected failure while loading games from DB", ex)
          RestoreGames(Map.empty)
      }

      // Enter initialization state while awaiting game restoration
      initializing(persistActor)
    }

  /**
    * Initialization state: waits for RestoreGames message after async DB load.
    * Transitions to running state once restoration is complete.
    */
  private def initializing(persistActor: ActorRef[PersistenceProtocol.Command]): Behavior[Command] =
    Behaviors.setup { implicit context =>
      Behaviors.receiveMessage {
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

          // Transition to running state after restoring all games
          running(restoredActors, persistActor)

        case other =>
          context.log.warn(s"Received unexpected message while initializing state: $other")
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
          val gameId = UUID.randomUUID().toString
          val actor = gameType match {
            case GameType.TicTacToe =>
              context.spawn(TicTacToeActor.create(gameId, players, persistActor), s"game-$gameId")
                .unsafeUpcast[GameActor.GameCommand]
          }
          context.log.info(s"Created new game with gameId: $gameId")
          replyTo ! gameId
          running(games + (gameId -> actor), persistActor)

        case ForwardToGame(gameId, msg, replyToOpt) =>
          games.get(gameId) match {
            case Some(gameActorRef) =>
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

                gameActorRef ! actualCommand
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

        case other =>
          context.log.warn(s"Unhandled message in running state: $other")
          Behaviors.same
      }
    }
}
