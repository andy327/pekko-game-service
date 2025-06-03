package com.andy327.server.actors

import scala.util.{Failure, Success}

import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.tictactoe._
import com.andy327.server.db.GameRepository
import com.andy327.server.http.TicTacToeStatus

object TicTacToeActor {
  sealed trait Command
  case class MakeMove(playerId: String, loc: Location, replyTo: ActorRef[Either[GameError, TicTacToeStatus]])
      extends Command
  case class GetStatus(replyTo: ActorRef[Either[GameError, TicTacToeStatus]]) extends Command
  private case class InternalLoadedState(maybeGame: Option[TicTacToe]) extends Command
  private case class InternalSaveResult(success: Boolean) extends Command

  implicit private val runtime: IORuntime = IORuntime.global // required for DB interaction

  private def markForPlayer(playerId: String, playerX: String, playerO: String): Option[Mark] =
    if (playerId == playerX) Some(X)
    else if (playerId == playerO) Some(O)
    else None

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
          context.log.info(s"Loaded game $gameId with state:\n$game")
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
  private def active(
      game: TicTacToe,
      playerX: String,
      playerO: String,
      gameId: String,
      gameRepo: GameRepository
  ): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case MakeMove(playerId, loc, replyTo) if game.gameStatus == InProgress =>
        markForPlayer(playerId, playerX, playerO) match {
          case Some(mark) if mark == game.currentPlayer =>
            game.play(mark, loc) match {
              case Right(nextState) =>
                context.log.info(s"Game $gameId updated:\n${nextState.render}")
                context.pipeToSelf(gameRepo.saveGame(gameId, nextState).unsafeToFuture()) {
                  case Success(_)  => InternalSaveResult(true)
                  case Failure(ex) =>
                    context.log.error(s"Error saving game $gameId", ex)
                    InternalSaveResult(false)
                }
                replyTo ! Right(convertStatus(nextState))
                active(nextState, playerX, playerO, gameId, gameRepo)

              case Left(error) =>
                context.log.warn(s"Invalid move by $mark at $loc: ${error.message}")
                replyTo ! Left(error)
                Behaviors.same
            }

          case Some(_) =>
            // Player is part of game, but it's not their turn
            replyTo ! Left(GameError.InvalidTurn)
            Behaviors.same

          case None =>
            // Player is not even part of the game
            replyTo ! Left(GameError.InvalidPlayer(s"Player ID '$playerId' is not part of this game."))
            Behaviors.same
        }

      case GetStatus(replyTo) =>
        replyTo ! Right(convertStatus(game))
        Behaviors.same

      case InternalSaveResult(success) =>
        if (!success) context.log.warn(s"Failed to save game state for $gameId")
        Behaviors.same

      case _ =>
        msg match {
          case MakeMove(_, _, replyTo) => replyTo ! Left(GameError.Unknown("Game is not ready or invalid command."))
          case GetStatus(replyTo)      => replyTo ! Right(convertStatus(game))
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
