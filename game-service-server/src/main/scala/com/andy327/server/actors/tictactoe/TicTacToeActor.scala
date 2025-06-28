package com.andy327.server.actors.tictactoe

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, PlayerId}
import com.andy327.model.tictactoe._
import com.andy327.server.actors.core.{GameActor, GameManager}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameStateConverters, TicTacToeState}
import com.andy327.server.lobby.GameLifecycleStatus

object TicTacToeActor extends GameActor[TicTacToe, TicTacToeState] {
  import TicTacToeState._

  sealed trait Command extends GameActor.GameCommand
  final case class MakeMove(player: PlayerId, loc: Location, replyTo: ActorRef[Either[GameError, TicTacToeState]])
      extends Command
  final case class GetState(replyTo: ActorRef[Either[GameError, TicTacToeState]]) extends Command

  sealed trait Internal extends Command
  final case class SnapshotSaved(result: Either[Throwable, Unit]) extends Internal
  final case class SnapshotLoaded(maybeGame: Either[Throwable, Option[TicTacToe]]) extends Internal

  private def markForPlayer(playerId: PlayerId, playerX: PlayerId, playerO: PlayerId): Option[Mark] =
    if (playerId == playerX) Some(X)
    else if (playerId == playerO) Some(O)
    else None

  /**
   * Initializes a new TicTacToeActor with an empty game.
   */
  override def create(
      gameId: String,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): (TicTacToe, Behavior[Command]) = {
    require(players.size == 2, "Tic-Tac-Toe needs exactly two players")
    val (playerX, playerO) = (players(0), players(1))

    val game = TicTacToe.empty(playerX, playerO)
    val behavior = Behaviors.setup[Command] { context =>
      context.log.info(s"[$gameId] starting new game")
      active(game, gameId, persist, gameManager)
    }

    (game, behavior)
  }

  /**
   * Creates a TicTacToeActor from a preloaded game snapshot.
   * This is used during recovery of games from persistent storage.
   */
  override def fromSnapshot(
      gameId: String,
      game: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): Behavior[Command] =
    Behaviors.setup { context =>
      game match {
        case ttt: TicTacToe => active(ttt, gameId, persist, gameManager)
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
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case MakeMove(_, _, replyTo) if game.gameStatus != InProgress =>
        context.log.warn(s"[$gameId] game already completed!")
        replyTo ! Left(GameError.GameOver)
        Behaviors.same

      case MakeMove(playerId, loc, replyTo) =>
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
                replyTo ! Right(GameStateConverters.serializeGame(nextState))

                nextState.gameStatus match {
                  case Won(_) | Draw =>
                    context.log.info(s"[$gameId] game completed with status: ${nextState.gameStatus}")
                    gameManager ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
                    active(nextState, gameId, persist, gameManager)

                  case InProgress =>
                    active(nextState, gameId, persist, gameManager)
                }

              case Left(err) =>
                context.log.warn(s"[$gameId] move rejected: $err")
                replyTo ! Left(err)
                Behaviors.same
            }

          case Some(_) =>
            context.log.warn(s"[$gameId] not the correct player's turn!")
            replyTo ! Left(GameError.InvalidTurn)
            Behaviors.same

          case None =>
            context.log.warn(s"[$gameId] Player ID '$playerId' is not part of this game.")
            replyTo ! Left(GameError.InvalidPlayer(playerId))
            Behaviors.same
        }

      case GetState(replyTo) =>
        replyTo ! Right(GameStateConverters.serializeGame(game))
        Behaviors.same

      case SnapshotSaved(result) =>
        result match {
          case Left(e)  => context.log.error(s"[$gameId] snapshot failed", e)
          case Right(_) => context.log.debug(s"[$gameId] snapshot saved successfully")
        }
        Behaviors.same

      case SnapshotLoaded(result) =>
        result match {
          case Left(e)  => context.log.error(s"[$gameId] snapshot load failed", e)
          case Right(_) => context.log.debug(s"[$gameId] snapshot loaded successfully")
        }
        Behaviors.same
    }
  }
}
