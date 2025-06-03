package com.andy327.server.actors

import java.util.UUID

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.tictactoe.{GameError, Location, TicTacToe}
import com.andy327.server.db.GameRepository
import com.andy327.server.http.TicTacToeStatus

object GameManager {
  sealed trait Command
  case class CreateTicTacToe(playerX: String, playerO: String, replyTo: ActorRef[String]) extends Command
  case class ForwardMove(gameId: String, playerId: String, loc: Location, replyTo: ActorRef[GameResponse])
      extends Command
  case class ForwardGetStatus(gameId: String, replyTo: ActorRef[GameResponse]) extends Command
  private case class RestoreGames(games: Map[String, TicTacToe]) extends Command
  private case class WrappedGameResponse(response: Either[GameError, TicTacToeStatus], replyTo: ActorRef[GameResponse])
      extends Command

  sealed trait GameResponse
  case class GameState(status: TicTacToeStatus) extends GameResponse
  case class ErrorResponse(message: String) extends GameResponse

  def apply(gameRepo: GameRepository): Behavior[Command] =
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
      initializing(gameRepo)
    }

  /**
    * Initialization state: waits for RestoreGames message after async DB load.
    * Transitions to running state once restoration is complete.
    */
  private def initializing(gameRepo: GameRepository): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case RestoreGames(games) =>
          val restoredActors = games.map { case (id, game) =>
            val actor = context.spawn(TicTacToeActor.fromSnapshot(id, game, gameRepo), s"game-$id")
            id -> actor
          }
          context.log.info(s"Initialized ${games.size} game actors from snapshots")

          // Transition to running state after restoring all games
          running(restoredActors, gameRepo)

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
      games: Map[String, ActorRef[TicTacToeActor.Command]],
      gameRepo: GameRepository
  ): Behavior[Command] =
    Behaviors.receive { (context, message) =>
      message match {
        case CreateTicTacToe(playerX, playerO, replyTo) =>
          val id = UUID.randomUUID().toString
          val actor = context.spawn(TicTacToeActor(playerX, playerO, id, gameRepo), s"game-$id")
          context.log.info(s"Created new game with id: $id")
          replyTo ! id
          running(games + (id -> actor), gameRepo)

        case ForwardMove(gameId, playerId, loc, replyTo) =>
          games.get(gameId) match {
            case Some(gameActorRef) =>
              val adapter = context.messageAdapter[Either[GameError, TicTacToeStatus]](wrapped =>
                WrappedGameResponse(wrapped, replyTo)
              )
              gameActorRef ! TicTacToeActor.MakeMove(playerId, loc, adapter)
            case None =>
              replyTo ! ErrorResponse(s"No game found with gameId $gameId")
          }
          Behaviors.same

        case ForwardGetStatus(gameId, replyTo) =>
          games.get(gameId) match {
            case Some(gameActorRef) =>
              val adapter = context.messageAdapter[Either[GameError, TicTacToeStatus]](wrapped =>
                WrappedGameResponse(wrapped, replyTo)
              )
              gameActorRef ! TicTacToeActor.GetStatus(adapter)
            case None =>
              replyTo ! ErrorResponse(s"No game found with gameId $gameId")
          }
          Behaviors.same

        case WrappedGameResponse(response, replyTo) =>
          response match {
            case Right(status) => replyTo ! GameState(status)
            case Left(error)   => replyTo ! ErrorResponse(error.message)
          }
          Behaviors.same

        case other =>
          context.log.warn(s"Unhandled message in running state: $other")
          Behaviors.same
      }
    }
}
