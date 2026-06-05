package com.andy327.server.actors.connectfour

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.connectfour.{ConnectFour, Drop, GameError, Mark, Red, Yellow}
import com.andy327.model.core.{Draw, Game, GameId, InProgress, PlayerId, Won}
import com.andy327.server.actors.core.{GameActor, GameManager, PlayerActor, PlayerEvent}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{ConnectFourState, GameStateConverters}
import com.andy327.server.lobby.GameLifecycleStatus
import com.andy327.server.pubsub.GameEventPublisher

/** [[com.andy327.server.actors.core.GameActor]] implementation for ConnectFour.
  *
  * Validates moves against a 6×7 board: checks turn order, player membership, column bounds, and column capacity.
  * Pieces fall to the lowest empty row (gravity). Detects a win (four in a line in any direction) or draw (board full)
  * after each drop.
  *
  * @see [[com.andy327.server.actors.core.GameActor]] for the full actor lifecycle and behavioral contract.
  */
object ConnectFourActor extends GameActor[ConnectFour] {
  import ConnectFourState._

  sealed trait Command extends GameActor.GameCommand

  // --- Commands received from GameManager (via GameRegistry routing) ---

  /** Attempt to drop a piece into `drop.col` on behalf of `playerId`. */
  final case class MakeMove(
      playerId: PlayerId,
      drop: Drop,
      replyTo: ActorRef[Either[GameError, ConnectFourState]]
  ) extends Command

  /** Return the current serialized board state without mutating it. */
  final case class GetState(replyTo: ActorRef[Either[GameError, ConnectFourState]]) extends Command

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

  /** Resolves `playerId` to `Red` or `Yellow` based on which seat they occupy; `None` if not a participant. */
  private def markForPlayer(playerId: PlayerId, playerRed: PlayerId, playerYellow: PlayerId): Option[Mark] =
    if (playerId == playerRed) Some(Red)
    else if (playerId == playerYellow) Some(Yellow)
    else None

  /** Initializes a new ConnectFourActor with an empty game. */
  override def create(
      gameId: GameId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: GameEventPublisher
  ): (ConnectFour, Behavior[Command]) = {
    val (playerRed, playerYellow) = (players(0), players(1))
    val game = ConnectFour.empty(playerRed, playerYellow)
    val behavior = Behaviors.setup[Command] { context =>
      context.log.info(s"[$gameId] starting new game")
      active(game, gameId, persist, gameManager, Set.empty, publisher)
    }
    (game, behavior)
  }

  /** Creates a ConnectFourActor from a preloaded game snapshot.
    *
    * Used during server startup to re-hydrate in-progress games from persistent storage. If the restored game is
    * already in a terminal state (won or draw), notifies GameManager and stops immediately without spawning an active
    * behavior. Stops the actor if the snapshot type does not match `ConnectFour`.
    */
  override def fromSnapshot(
      gameId: GameId,
      game: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: GameEventPublisher
  ): Behavior[Command] =
    Behaviors.setup { context =>
      game match {
        case cf: ConnectFour =>
          cf.gameStatus match {
            case InProgress =>
              context.log.info(s"[$gameId] restored in-progress game")
              active(cf, gameId, persist, gameManager, Set.empty, publisher)
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

  /** Core recursive behavior that drives a single game from first drop to completion.
    *
    * Each state-changing message (MakeMove) produces a new `active` behavior with updated game and subscriber state.
    * Read-only messages (GetState, SnapshotSaved) return `Behaviors.same`. Subscribe/Unsubscribe update the subscriber
    * set. On game completion the actor transitions to [[terminating]] and stops itself after the final snapshot is
    * confirmed.
    */
  private def active(
      game: ConnectFour,
      gameId: GameId,
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      subscribers: Set[ActorRef[PlayerActor.Command]],
      publisher: GameEventPublisher
  ): Behavior[Command] = Behaviors.receive { (context, msg) =>
    msg match {
      case MakeMove(playerId, drop, replyTo) =>
        markForPlayer(playerId, game.playerRed, game.playerYellow) match {
          case Some(mark) if mark == game.currentPlayer =>
            game.play(mark, drop) match {
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
                val stateEvent = PlayerEvent.GameStateUpdated(serialized)
                subscribers.foreach(_ ! PlayerActor.SendEvent(stateEvent))
                publisher.publish(gameId, stateEvent)

                nextState.gameStatus match {
                  case Won(_) | Draw =>
                    context.log.info(s"[$gameId] game completed with status: ${nextState.gameStatus}")
                    val endEvent = PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
                    subscribers.foreach(_ ! PlayerActor.SendEvent(endEvent))
                    publisher.publish(gameId, endEvent)
                    gameManager ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
                    terminating(gameId)

                  case InProgress =>
                    active(nextState, gameId, persist, gameManager, subscribers, publisher)
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
        active(game, gameId, persist, gameManager, subscribers + playerRef, publisher)

      case Unsubscribe(playerRef) =>
        active(game, gameId, persist, gameManager, subscribers - playerRef, publisher)

      case SnapshotSaved(result) =>
        result match {
          case Left(e)  => context.log.error(s"[$gameId] snapshot failed", e)
          case Right(_) => context.log.debug(s"[$gameId] snapshot saved successfully")
        }
        Behaviors.same

    }
  }
}
