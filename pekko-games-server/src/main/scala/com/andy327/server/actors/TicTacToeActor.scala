package com.andy327.server.actors

import com.andy327.model.tictactoe._
import com.andy327.server.db.GameRepository
import com.andy327.server.http.{Move, TicTacToeStatus}

import cats.effect.unsafe.IORuntime
import cats.effect.IO
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

import scala.util.{Failure, Success}

object TicTacToeActor {
  sealed trait Command
  case class MakeMove(playerId: String, loc: Location, replyTo: ActorRef[TicTacToeStatus]) extends Command
  case class GetStatus(replyTo: ActorRef[TicTacToeStatus]) extends Command
  private case class InternalLoadedState(maybeGame: Option[TicTacToe]) extends Command
  private case class InternalSaveResult(success: Boolean) extends Command

  private implicit val runtime: IORuntime = IORuntime.global // required for DB interaction

  /**
   * Creates a TicTacToeActor from a preloaded game snapshot.
   * This is used during recovery of games from persistent storage.
   */
  def fromSnapshot(gameId: String, game: TicTacToe, repo: GameRepository): Behavior[Command] =
    active(game, game.playerX, game.playerO, gameId, repo)

  /**
   * Initializes a new TicTacToeActor.
   * Attempts to load existing state from DB; otherwise starts fresh.
   */
  def apply(playerX: String, playerO: String, gameId: String, gameRepo: GameRepository): Behavior[Command] =
    Behaviors.setup { context =>
      // Ask repository to load any existing state for this gameId
      context.pipeToSelf(gameRepo.loadGame(gameId).unsafeToFuture()) {
        case Success(gameOpt) => InternalLoadedState(gameOpt)
        case Failure(_)       => InternalLoadedState(None)
      }

      loading(playerX, playerO, gameId, gameRepo)
    }

  /**
   * Temporary behavior while game state is being loaded.
   * Transitions to `active` once loading completes.
   */
  private def loading(playerX: String, playerO: String, gameId: String, gameRepo: GameRepository): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      msg match {
        case InternalLoadedState(maybeGame) =>
          val game = maybeGame.getOrElse(TicTacToe.empty(playerX, playerO))
          context.log.info(s"Loaded game $gameId with state: $game")
          active(game, playerX, playerO, gameId, gameRepo)

        case _ =>
          // Ignore messages received during loading
          Behaviors.same
      }
    }

  /**
   * Main actor behavior.
   * Processes game logic and player interactions.
   */
  private def active(game: TicTacToe, playerX: String, playerO: String,
                     gameId: String, gameRepo: GameRepository): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case MakeMove(playerId, loc, replyTo) if game.gameStatus == InProgress &&
          ((game.currentPlayer == X && playerId == playerX) || (game.currentPlayer == O && playerId == playerO)) =>

        game.play(loc) match {
          case Right(nextState: TicTacToe) =>
            // Log updated board rendering
            context.log.info(s"Game $gameId updated:\n${nextState.render}")

            // Save the updated game state to the DB asynchronously
            context.pipeToSelf(gameRepo.saveGame(gameId, nextState).unsafeToFuture()) {
              case Success(_) => InternalSaveResult(true)
              case Failure(ex) =>
                context.log.error(s"Error saving game $gameId", ex)
                InternalSaveResult(false)
            }
            replyTo ! convertStatus(nextState)
            active(nextState, playerX, playerO, gameId, gameRepo)

          case Left(_) =>
            // Invalid move; return current status unchanged
            replyTo ! convertStatus(game)
            Behaviors.same
        }

      case GetStatus(replyTo) =>
        replyTo ! convertStatus(game)
        Behaviors.same

      case InternalSaveResult(success) =>
        if (!success) context.log.warn(s"Failed to save game state for $gameId")
        Behaviors.same

      case _ =>
        msg match {
          case MakeMove(_, _, replyTo) => replyTo ! convertStatus(game)
          case GetStatus(replyTo)      => replyTo ! convertStatus(game)
          case _                       => // ignore other commands
        }
        Behaviors.same
    }
  }

  /**
   * Converts the internal game model into a serializable HTTP response.
   */
  private def convertStatus(game: TicTacToe): TicTacToeStatus = {
    val boardStrings = game.board.map(_.map(_.map(_.toString).getOrElse("")))
    val currentPlayer = game.currentPlayer.toString
    val winnerOpt = game.gameStatus match {
      case Won(mark) => Some(mark.toString)
      case _         => None
    }
    val draw = game.gameStatus == Draw
    TicTacToeStatus(boardStrings, currentPlayer, winnerOpt, draw)
  }
}
