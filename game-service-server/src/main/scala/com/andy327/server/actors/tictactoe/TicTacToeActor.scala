package com.andy327.server.actors.tictactoe

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.Game
import com.andy327.model.tictactoe._
import com.andy327.server.actors.core.GameActor
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameState, TicTacToeState}

object TicTacToeActor extends GameActor[TicTacToe] {
  sealed trait Command extends GameActor.GameCommand
  case class MakeMove(playerId: String, loc: Location, replyTo: ActorRef[Either[GameError, GameState]]) extends Command
  case class GetState(replyTo: ActorRef[Either[GameError, GameState]]) extends Command

  sealed private trait Internal extends Command
  final private case class SnapshotLoaded(maybeGame: Either[Throwable, Option[TicTacToe]]) extends Internal
  final private case class SnapshotSaved(result: Either[Throwable, Unit]) extends Internal

  private case class InternalLoadedState(maybeGame: Option[TicTacToe]) extends Command
  private case class InternalSaveResult(success: Boolean) extends Command

  private def markForPlayer(playerId: String, playerX: String, playerO: String): Option[Mark] =
    if (playerId == playerX) Some(X)
    else if (playerId == playerO) Some(O)
    else None

  /**
   * Converts the internal game model into a serializable HTTP response.
   */
  override def serializableGameState(game: TicTacToe): GameState = {
    val boardStrings = game.board.map(_.map(_.map(_.toString).getOrElse("")))
    val currentPlayer = game.currentPlayer.toString
    val winnerOpt = game.gameStatus match {
      case Won(mark) => Some(mark.toString)
      case _         => None
    }
    val draw = game.gameStatus == Draw
    TicTacToeState(boardStrings, currentPlayer, winnerOpt, draw)
  }

  /**
   * Initializes a new TicTacToeActor.
   * Attempts to load existing state from DB; otherwise starts fresh.
   */
  override def create(
      gameId: String,
      players: Seq[String],
      persist: ActorRef[PersistenceProtocol.Command]
  ): Behavior[Command] = {
    require(players.size == 2, "Tic‑Tac‑Toe needs exactly two players")
    val (playerX, playerO) = (players(0), players(1))

    val newGame = TicTacToe.empty(playerX, playerO)

    Behaviors.setup { context =>
      context.log.info(s"[$gameId] starting new game")
      active(newGame, gameId, persist)
    }
  }

  /**
   * Creates a TicTacToeActor from a preloaded game snapshot.
   * This is used during recovery of games from persistent storage.
   */
  override def fromSnapshot(
      gameId: String,
      game: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command]
  ): Behavior[Command] =
    Behaviors.setup { context =>
      game match {
        case ttt: TicTacToe => active(ttt, gameId, persist)
        case _              =>
          context.log.error(s"Unexpected snapshot type for game $gameId: $game")
          Behaviors.stopped
      }
    }

  /**
   * Main actor behavior.
   * Processes game logic and player interactions.
   */
  private def active(
      game: TicTacToe,
      gameId: String,
      persist: ActorRef[PersistenceProtocol.Command]
  ): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case MakeMove(playerId, loc, replyTo) if game.gameStatus == InProgress =>
        markForPlayer(playerId, game.playerX, game.playerO) match {
          case Some(mark) if mark == game.currentPlayer =>
            game.play(mark, loc) match {
              case Right(nextState) =>
                context.log.info(s"Game $gameId updated:\n${nextState.render}")

                // Persist in background
                persist ! PersistenceProtocol.SaveSnapshot(
                  gameId = gameId,
                  gameType = gameType,
                  game = nextState,
                  replyTo = context.messageAdapter(_ => SnapshotSaved(Right(())))
                )
                replyTo ! Right(serializableGameState(nextState))

                active(nextState, gameId, persist)

              case Left(err) =>
                replyTo ! Left(err)
                Behaviors.same
            }

          case Some(_) =>
            // Player is part of the game, but it's not their turn
            replyTo ! Left(GameError.InvalidTurn)
            Behaviors.same

          case None =>
            // Player is not even part of the game
            replyTo ! Left(GameError.InvalidPlayer(s"Player ID '$playerId' is not part of this game."))
            Behaviors.same
        }

      case GetState(replyTo) =>
        replyTo ! Right(serializableGameState(game))
        Behaviors.same

      case SnapshotSaved(Left(throwable)) =>
        context.log.error(s"[$gameId] snapshot failed", throwable)
        Behaviors.same

      case SnapshotSaved(Right(_)) =>
        // Success – no action
        Behaviors.same

      case other =>
        context.log.warn(s"[$gameId] unknown message: $other")
        Behaviors.same
    }
  }
}
