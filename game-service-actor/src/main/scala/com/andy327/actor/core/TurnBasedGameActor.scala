package com.andy327.actor.core

import scala.reflect.ClassTag

import io.circe.Encoder
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}

import com.andy327.actor.events.{EventPublisher, GameEvent}
import com.andy327.actor.game.{GameState, GameStateView}
import com.andy327.actor.lobby.GameLifecycleStatus
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{Draw, Game, GameError, GameId, GameStatus, GameTypeTag, InProgress, PlayerId, Won}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

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

  /** Apply the effect of `playerId` leaving the game (a forfeit for two-player games). Replies with the leaver's view
    * of the resulting state, or a `GameError` if the model rejects the leave (e.g. not a participant, or unsupported).
    */
  final case class PlayerLeft(playerId: PlayerId, replyTo: ActorRef[Either[GameError, GameState]])
      extends Command[Nothing]

  /** Return the current serialized game state without mutating it. */
  final case class GetState(replyTo: ActorRef[Either[GameError, GameState]]) extends Command[Nothing]

  /** Register a PlayerActor (the session for `playerId`) to receive push events (state updates and game-end
    * notifications). `playerId` identifies the viewer so each subscriber can be sent their own rendering of the state.
    */
  final case class Subscribe(playerRef: ActorRef[PlayerActor.Command], playerId: PlayerId) extends Command[Nothing]

  /** Deregister a previously subscribed PlayerActor. */
  final case class Unsubscribe(playerRef: ActorRef[PlayerActor.Command]) extends Command[Nothing]

  /** Fan an already-built event (e.g. a chat message) out to the game's current subscribers. */
  final case class Broadcast(event: PlayerEvent) extends Command[Nothing]

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
  *   object MyGameActor extends TurnBasedGameActor[MyGame, MyMove, MySeat, MyGameState](
  *     players => MyGame.empty(players(0), players(1)),
  *     deriveEncoder[MyMove]
  *   )
  * }}}
  * given a `GameTypeTag[MyGame]` (model) and a `GameStateView[MyGame, MyGameState]` (http.json) instance.
  *
  * @param newGame factory producing the initial game model from the seated players; the player count has already been
  *                validated against the GameType's bounds by [[GameManager]]
  * @param moveEncoder serializes a move to JSON for the append-only history log
  * @tparam G the concrete game model type
  * @tparam M the game's move type
  * @tparam P the game's player-token (seat) type
  * @tparam S the serializable view produced for HTTP/WebSocket delivery
  * @see [[GameActor]] for the full actor lifecycle and behavioral contract.
  */
class TurnBasedGameActor[G <: Game[M, G, P, GameStatus[P], GameError], M, P, S <: GameState](
    newGame: Seq[PlayerId] => G,
    moveEncoder: Encoder[M]
)(implicit tag: GameTypeTag[G], view: GameStateView[G, S], ct: ClassTag[G])
    extends GameActor[G] {

  import TurnBasedGameActor._

  type Command = TurnBasedGameActor.Command[M]

  override def subscribeCommand(playerRef: ActorRef[PlayerActor.Command], playerId: PlayerId): GameActor.GameCommand =
    Subscribe(playerRef, playerId)

  override def unsubscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand =
    Unsubscribe(playerRef)

  override def broadcastCommand(event: PlayerEvent): GameActor.GameCommand =
    Broadcast(event)

  override def forfeitCommand(
      playerId: PlayerId,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): GameActor.GameCommand =
    PlayerLeft(playerId, replyTo)

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
      publisher: EventPublisher
  ): (G, Behavior[Command]) = {
    val game = newGame(players)
    val behavior = Behaviors.setup[Command] { context =>
      context.log.info(s"[$gameId] starting new game")
      active(game, gameId, persist, gameManager, Map.empty, publisher)
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
      publisher: EventPublisher
  ): Behavior[Command] =
    Behaviors.setup { context =>
      snap match {
        case game: G =>
          game.gameStatus match {
            case InProgress =>
              context.log.info(s"[$gameId] restored in-progress game")
              active(game, gameId, persist, gameManager, Map.empty, publisher)
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

  /** Persists, fans out, and completes a successful state transition — shared by `MakeMove` and `PlayerLeft`.
    *
    * Saves a snapshot, replies to the acting player with their own view, and fans a per-viewer view out to every
    * subscriber. The resulting `gameStatus` then decides the next behavior: a terminal status emits `GameEnded` to
    * subscribers, publishes a `GameCompleted` analytics event, notifies GameManager, and transitions to
    * [[terminating]]; an `InProgress` status returns to [[active]] with the new state.
    *
    * @param viewerId the acting player (mover or leaver), used to render the reply sent back to them
    * @param forfeit true when the transition is a leave/forfeit rather than a normal move; selects the `Forfeit`
    *                outcome for the completion analytics event
    * @param appendMove run immediately after the snapshot save — a move appends to the history log and emits a
    *                   `MoveMade` analytics event here, whereas a forfeit passes the default no-op (the winning result
    *                   is derived, not a recorded move)
    */
  private def applyTransition(
      context: ActorContext[Command],
      nextState: G,
      viewerId: PlayerId,
      gameId: GameId,
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      subscribers: Map[ActorRef[PlayerActor.Command], PlayerId],
      publisher: EventPublisher,
      forfeit: Boolean,
      replyTo: ActorRef[Either[GameError, GameState]]
  )(appendMove: => Unit = ()): Behavior[Command] = {
    context.log.info(s"Game $gameId updated:\n$nextState")

    persist ! PersistenceProtocol.SaveSnapshot(
      gameId = gameId,
      gameType = gameType,
      game = nextState,
      // pass the real save outcome through so a failed snapshot is logged by the SnapshotSaved handler
      replyTo = context.messageAdapter(saved => SnapshotSaved(saved.result))
    )

    appendMove

    // reply to the acting player with their own view; fan out a per-viewer view to each subscriber
    replyTo ! Right(view.fromGame(nextState, Some(viewerId)))
    subscribers.foreach { case (ref, subscriberId) =>
      ref ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(view.fromGame(nextState, Some(subscriberId))))
    }

    nextState.gameStatus match {
      case Won(_) | Draw =>
        context.log.info(s"[$gameId] game completed with status: ${nextState.gameStatus}")
        val endEvent = PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
        // GameEnded carries no board, so it is identical for every viewer
        subscribers.foreach { case (ref, _) => ref ! PlayerActor.SendEvent(endEvent) }

        // derive each participant's per-player result and the aggregate analytics outcome from the terminal status; a
        // forfeit is itself a win for the remaining player, so the winner-vs-seat comparison already yields the right
        // win/loss split — `forfeit` only flags how the win was reached
        val (results, outcome) = nextState.gameStatus match {
          case Won(winner) =>
            val rs = nextState.players.map { pid =>
              pid -> (if (nextState.playerFor(pid).contains(winner)) GameResult.Win else GameResult.Loss)
            }
            (rs, if (forfeit) GameEvent.Outcome.Forfeit else GameEvent.Outcome.Won)
          case _ =>
            (nextState.players.map(_ -> GameResult.Draw), GameEvent.Outcome.Draw)
        }
        results.foreach { case (pid, result) =>
          persist ! PersistenceProtocol.RecordGameResult(pid, gameId, gameType, result, forfeit)
        }
        publisher.publish(GameEvent.GameCompleted(gameId, gameType, outcome, nextState.moveCount))
        gameManager ! GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
        terminating(gameId)

      case InProgress =>
        active(nextState, gameId, persist, gameManager, subscribers, publisher)
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
      subscribers: Map[ActorRef[PlayerActor.Command], PlayerId],
      publisher: EventPublisher
  ): Behavior[Command] = Behaviors.receive[Command] { (context, msg) =>
    msg match {
      case MakeMove(playerId, move, replyTo) =>
        game.playerFor(playerId) match {
          // turn order is validated by the model: game.play returns InvalidTurn for an out-of-turn player
          case Some(player) =>
            game.play(player, move) match {
              case Right(nextState) =>
                applyTransition(
                  context,
                  nextState,
                  playerId,
                  gameId,
                  persist,
                  gameManager,
                  subscribers,
                  publisher,
                  forfeit = false,
                  replyTo
                ) {
                  // record the move in the append-only history log; seq is the pre-move count (0-based ordinal)
                  persist ! PersistenceProtocol.AppendMove(gameId, game.moveCount, playerId, moveEncoder(move))
                  publisher.publish(GameEvent.MoveMade(gameId, gameType, playerId, game.moveCount))
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

      case PlayerLeft(playerId, replyTo) =>
        // forfeit/fold-out semantics live in the model; the resulting status drives completion exactly like a move
        game.playerLeft(playerId) match {
          case Right(nextState) =>
            context.log.info(s"[$gameId] player $playerId left the game")
            applyTransition(
              context,
              nextState,
              playerId,
              gameId,
              persist,
              gameManager,
              subscribers,
              publisher,
              forfeit = true,
              replyTo
            )()

          case Left(err) =>
            context.log.warn(s"[$gameId] leave rejected: $err")
            replyTo ! Left(err)
            Behaviors.same
        }

      case GetState(replyTo) =>
        replyTo ! Right(view.fromGame(game, None))
        Behaviors.same

      case Subscribe(playerRef, playerId) =>
        // watch the subscriber so its termination (disconnect/reconnect) drops it from the map automatically
        context.watch(playerRef)
        playerRef ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(view.fromGame(game, Some(playerId))))
        active(game, gameId, persist, gameManager, subscribers + (playerRef -> playerId), publisher)

      case Unsubscribe(playerRef) =>
        context.unwatch(playerRef)
        active(game, gameId, persist, gameManager, subscribers - playerRef, publisher)

      case Broadcast(event) =>
        // chat and other broadcasts carry no hidden state, so every viewer gets the same event
        subscribers.foreach { case (ref, _) => ref ! PlayerActor.SendEvent(event) }
        Behaviors.same

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
    active(game, gameId, persist, gameManager, subscribers.filterNot { case (r, _) => r == ref }, publisher)
  }
}
