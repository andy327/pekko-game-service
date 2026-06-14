package com.andy327.server.actors.core

import scala.reflect.ClassTag

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}

import com.andy327.model.core.{Draw, Game, GameError, GameId, GameStatus, GameTypeTag, InProgress, PlayerId, Won}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameState, GameStateView}
import com.andy327.server.lobby.GameLifecycleStatus
import com.andy327.server.pubsub.GameEventPublisher

object TurnBasedGameActor {

  /** Commands understood by every turn-based game actor.
    *
    * Parameterized (covariantly) on the game's move type `M` so that `MakeMove` carries the concrete move while the
    * move-independent commands extend `Command[Nothing]` and fit any game's actor.
    */
  sealed trait Command[+M] extends GameActor.GameCommand

  /** Attempt to apply `move` on behalf of `playerId`. */
  final case class MakeMove[M](playerId: PlayerId, move: M, replyTo: ActorRef[Either[GameError, GameState]])
      extends Command[M]

  /** Return the current serialized game state without mutating it. */
  final case class GetState(replyTo: ActorRef[Either[GameError, GameState]]) extends Command[Nothing]

  /** Register a PlayerActor to receive push events (state updates and game-end notifications). */
  final case class Subscribe(playerRef: ActorRef[PlayerActor.Command]) extends Command[Nothing]

  /** Deregister a previously subscribed PlayerActor. */
  final case class Unsubscribe(playerRef: ActorRef[PlayerActor.Command]) extends Command[Nothing]

  /** Delivered by a `messageAdapter` after PersistenceProtocol.SaveSnapshot completes; logged but never causes a
    * state change while the game is active.
    */
  final case class SnapshotSaved(result: Either[Throwable, Unit]) extends Command[Nothing]
}

/** The single [[GameActor]] implementation shared by every turn-based game.
  *
  * All game-specific rules live in the model: `game.play` validates and applies moves (including turn order), and
  * `game.playerFor` resolves a platform PlayerId to the game's player token. What remains — persistence, subscriber
  * fan-out, event publishing, and lifecycle — is identical across games and implemented once here.
  *
  * Adding a new game type therefore requires no actor code beyond a binding:
  * {{{
  *   object MyGameActor extends TurnBasedGameActor[MyGame, MyMove, MySeat, MyGameState](players =>
  *     MyGame.empty(players(0), players(1))
  *   )
  * }}}
  * given a `GameTypeTag[MyGame]` (model) and a `GameStateView[MyGame, MyGameState]` (http.json) instance.
  *
  * @param newGame factory producing the initial game model from the seated players; the player count has already been
  *                validated against the GameType's bounds by [[GameManager]]
  * @tparam G the concrete game model type
  * @tparam M the game's move type
  * @tparam P the game's player-token (seat) type
  * @tparam S the serializable view produced for HTTP/WebSocket delivery
  * @see [[GameActor]] for the full actor lifecycle and behavioral contract.
  */
class TurnBasedGameActor[G <: Game[M, G, P, GameStatus[P], GameError], M, P, S <: GameState](
    newGame: Seq[PlayerId] => G
)(implicit tag: GameTypeTag[G], view: GameStateView[G, S], ct: ClassTag[G])
    extends GameActor[G] {

  import TurnBasedGameActor._

  type Command = TurnBasedGameActor.Command[M]

  override def subscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand =
    Subscribe(playerRef)

  override protected def snapshotSavedResult(cmd: Command): Option[Either[Throwable, Unit]] = cmd match {
    case SnapshotSaved(result) => Some(result)
    case _                     => None
  }

  /** Initializes a new game actor with an empty game. */
  override def create(
      gameId: GameId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: GameEventPublisher
  ): (G, Behavior[Command]) = {
    val game = newGame(players)
    val behavior = Behaviors.setup[Command] { context =>
      context.log.info(s"[$gameId] starting new game")
      active(game, gameId, persist, gameManager, Set.empty, publisher)
    }
    (game, behavior)
  }

  /** Creates a game actor from a preloaded game snapshot.
    *
    * Used during server startup to re-hydrate in-progress games from persistent storage. If the restored game is
    * already in a terminal state (won or draw), notifies GameManager and stops immediately without spawning an active
    * behavior. Stops the actor if the snapshot type does not match `G`.
    */
  override def fromSnapshot(
      gameId: GameId,
      snap: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: GameEventPublisher
  ): Behavior[Command] =
    Behaviors.setup { context =>
      snap match {
        case game: G =>
          game.gameStatus match {
            case InProgress =>
              context.log.info(s"[$gameId] restored in-progress game")
              active(game, gameId, persist, gameManager, Set.empty, publisher)
            case Won(_) | Draw =>
              context.log.info(s"[$gameId] restored as already-completed game — notifying GameManager and stopping")
              gameManager ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
              Behaviors.stopped
          }
        case _ =>
          context.log.error(s"Unexpected snapshot type for game $gameId: $snap")
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
      game: G,
      gameId: GameId,
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      subscribers: Set[ActorRef[PlayerActor.Command]],
      publisher: GameEventPublisher
  ): Behavior[Command] = Behaviors.receive[Command] { (context, msg) =>
    msg match {
      case MakeMove(playerId, move, replyTo) =>
        game.playerFor(playerId) match {
          // turn order is validated by the model: game.play returns InvalidTurn for an out-of-turn player
          case Some(player) =>
            game.play(player, move) match {
              case Right(nextState) =>
                context.log.info(s"Game $gameId updated:\n$nextState")

                persist ! PersistenceProtocol.SaveSnapshot(
                  gameId = gameId,
                  gameType = gameType,
                  game = nextState,
                  replyTo = context.messageAdapter(_ => SnapshotSaved(Right(())))
                )

                val serialized = view.fromGame(nextState)
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

          case None =>
            context.log.warn(s"[$gameId] Player ID '$playerId' is not part of this game.")
            replyTo ! Left(GameError.InvalidPlayer(playerId))
            Behaviors.same
        }

      case GetState(replyTo) =>
        replyTo ! Right(view.fromGame(game))
        Behaviors.same

      case Subscribe(playerRef) =>
        // watch the subscriber so its termination (disconnect/reconnect) drops it from the set automatically
        context.watch(playerRef)
        playerRef ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(view.fromGame(game)))
        active(game, gameId, persist, gameManager, subscribers + playerRef, publisher)

      case Unsubscribe(playerRef) =>
        context.unwatch(playerRef)
        active(game, gameId, persist, gameManager, subscribers - playerRef, publisher)

      case SnapshotSaved(result) =>
        result match {
          case Left(e)  => context.log.error(s"[$gameId] snapshot failed", e)
          case Right(_) => context.log.debug(s"[$gameId] snapshot saved successfully")
        }
        Behaviors.same
    }
  }.receiveSignal { case (context, Terminated(ref)) =>
    // a subscribed PlayerActor stopped; drop its (now-dead) ref so we stop fanning events to it
    context.log.debug(s"[$gameId] subscriber $ref terminated; removing from subscribers")
    active(game, gameId, persist, gameManager, subscribers.filterNot(_ == ref), publisher)
  }
}
