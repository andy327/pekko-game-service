package com.andy327.server.actors.tictactoe

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameId, PlayerId}
import com.andy327.model.tictactoe._
import com.andy327.server.actors.core.{GameActor, GameManager, PlayerActor, PlayerEvent}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameStateConverters, TicTacToeState}
import com.andy327.server.lobby.GameLifecycleStatus

/** [[com.andy327.server.actors.core.GameActor]] implementation for TicTacToe.
  *
  * Validates moves against a 3×3 board: checks turn order, player membership, cell occupancy, and bounds.
  * Detects a win (three in a row/column/diagonal) or draw (board full) after each move.
  *
  * @see [[com.andy327.server.actors.core.GameActor]] for the full actor lifecycle and behavioral contract.
  */
object TicTacToeActor extends GameActor[TicTacToe] {
  import TicTacToeState._

  sealed trait Command extends GameActor.GameCommand

  // --- Commands received from GameManager (via GameRegistry routing) ---

  /** Attempt to apply a move at `loc` on behalf of `playerId`. */
  final case class MakeMove(playerId: PlayerId, loc: Location, replyTo: ActorRef[Either[GameError, TicTacToeState]])
      extends Command

  /** Return the current serialized board state without mutating it. */
  final case class GetState(replyTo: ActorRef[Either[GameError, TicTacToeState]]) extends Command

  /** Register a PlayerActor to receive push events (state updates and game-end notifications). */
  final case class Subscribe(playerRef: ActorRef[PlayerActor.Command]) extends Command

  /** Deregister a previously subscribed PlayerActor. */
  final case class Unsubscribe(playerRef: ActorRef[PlayerActor.Command]) extends Command

  // --- Internal messages (adapter results from the persistence actor) ---

  /** Wraps the result of a fire-and-forget snapshot save; logged but never causes a state change. */
  sealed trait Internal extends Command

  /** Delivered by a `messageAdapter` after PersistenceProtocol.SaveSnapshot completes. */
  final case class SnapshotSaved(result: Either[Throwable, Unit]) extends Internal

  override def subscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand =
    Subscribe(playerRef)

  override protected def snapshotSavedResult(cmd: Command): Option[Either[Throwable, Unit]] = cmd match {
    case SnapshotSaved(result) => Some(result)
    case _                     => None
  }

  /** Resolves `playerId` to `X` or `O` based on which seat they occupy; `None` if not a participant. */
  private def markForPlayer(playerId: PlayerId, playerX: PlayerId, playerO: PlayerId): Option[Mark] =
    if (playerId == playerX) Some(X)
    else if (playerId == playerO) Some(O)
    else None

  /** Initializes a new TicTacToeActor with an empty game. */
  override def create(
      gameId: GameId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): (TicTacToe, Behavior[Command]) = {
    val (playerX, playerO) = (players(0), players(1))

    val game = TicTacToe.empty(playerX, playerO)
    val behavior = Behaviors.setup[Command] { context =>
      context.log.info(s"[$gameId] starting new game")
      active(game, gameId, persist, gameManager, Set.empty)
    }

    (game, behavior)
  }

  /** Creates a TicTacToeActor from a preloaded game snapshot.
    *
    * Used during server startup to re-hydrate in-progress games from persistent storage. If the restored game is
    * already in a terminal state (won or draw), notifies GameManager and stops immediately without spawning an active
    * behavior. Stops the actor if the snapshot type does not match `TicTacToe`.
    */
  override def fromSnapshot(
      gameId: GameId,
      game: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): Behavior[Command] =
    Behaviors.setup { context =>
      game match {
        case ttt: TicTacToe =>
          ttt.gameStatus match {
            case InProgress =>
              context.log.info(s"[$gameId] restored in-progress game")
              active(ttt, gameId, persist, gameManager, Set.empty)
            case Won(_) | Draw =>
              context.log.info(s"[$gameId] restored as already-completed game — notifying GameManager and stopping")
              gameManager ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
              Behaviors.stopped
          }
        case _ =>
          context.log.error(s"Unexpected snapshot type for game $gameId: $game")
          Behaviors.stopped
      }
    }

  /** Core recursive behavior that drives a single game from first move to completion.
    *
    * Each state-changing message (MakeMove) produces a new `active` behavior with updated game and subscriber state.
    * Read-only messages (GetState, SnapshotSaved) return `Behaviors.same`. Subscribe/Unsubscribe update the subscriber
    * set. On game completion the actor transitions to [[terminating]] and stops itself after the final snapshot is
    * confirmed.
    */
  private def active(
      game: TicTacToe,
      gameId: GameId,
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      subscribers: Set[ActorRef[PlayerActor.Command]]
  ): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case MakeMove(playerId, loc, replyTo) =>
        markForPlayer(playerId, game.playerX, game.playerO) match {
          case Some(mark) if mark == game.currentPlayer =>
            game.play(mark, loc) match {
              case Right(nextState) =>
                context.log.info(s"Game $gameId updated:\n${nextState.render}")

                persist ! PersistenceProtocol.SaveSnapshot(
                  gameId = gameId,
                  gameType = gameType,
                  game = nextState,
                  replyTo = context.messageAdapter(_ => SnapshotSaved(Right(())))
                )

                val serialized = GameStateConverters.serializeGame(nextState)
                replyTo ! Right(serialized)
                subscribers.foreach(_ ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(serialized)))

                nextState.gameStatus match {
                  case Won(_) | Draw =>
                    context.log.info(s"[$gameId] game completed with status: ${nextState.gameStatus}")
                    subscribers.foreach(_ ! PlayerActor.SendEvent(PlayerEvent.GameEnded(GameLifecycleStatus.Completed)))
                    gameManager ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
                    terminating(gameId)

                  case InProgress =>
                    active(nextState, gameId, persist, gameManager, subscribers)
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

      case Subscribe(playerRef) =>
        playerRef ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(GameStateConverters.serializeGame(game)))
        active(game, gameId, persist, gameManager, subscribers + playerRef)

      case Unsubscribe(playerRef) =>
        active(game, gameId, persist, gameManager, subscribers - playerRef)

      case SnapshotSaved(result) =>
        result match {
          case Left(e)  => context.log.error(s"[$gameId] snapshot failed", e)
          case Right(_) => context.log.debug(s"[$gameId] snapshot saved successfully")
        }
        Behaviors.same

    }
  }
}
