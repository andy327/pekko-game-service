package com.andy327.actor.core

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import io.circe.Encoder
import org.apache.pekko.actor.typed.scaladsl.{ActorContext, Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}

import com.andy327.actor.events.{EventPublisher, GameEvent}
import com.andy327.actor.game.{GameProjection, GameView}
import com.andy327.actor.lobby.{BotId, GameLifecycleStatus}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{
  Draw,
  Game,
  GameError,
  GameStatus,
  GameTypeTag,
  InProgress,
  MatchId,
  PlayerId,
  RoomId,
  Won
}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

object TurnBasedGameActor {

  /** Commands understood by every turn-based game actor.
    *
    * Parameterized (covariantly) on the game's move type `M` so that `MakeMove` carries the concrete move while the
    * move-independent commands extend `Command[Nothing]` and fit any game's actor.
    */
  sealed trait Command[+M] extends GameActor.GameCommand

  /** Attempt to apply `move` on behalf of `playerId`. */
  final case class MakeMove[M](playerId: PlayerId, move: M, replyTo: ActorRef[Either[GameError, GameView]])
      extends Command[M]

  /** Apply the effect of `playerId` leaving the game (a forfeit for two-player games). Replies with the leaver's view
    * of the resulting state, or a `GameError` if the model rejects the leave (e.g. not a participant, or unsupported).
    */
  final case class PlayerLeft(playerId: PlayerId, replyTo: ActorRef[Either[GameError, GameView]])
      extends Command[Nothing]

  /** Return the current game state, projected for a spectator, without mutating it. */
  final case class GetState(replyTo: ActorRef[Either[GameError, GameView]]) extends Command[Nothing]

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

  /** Fired by the per-turn timer when `playerId`'s clock expires. Keyed to the turn it was armed for: `forMoveCount` is
    * the game's `moveCount` at arm time, so a timeout that arrives after the player already moved (and the game
    * advanced) no longer matches the current turn and is ignored by the active behavior's handler. Internal — only the
    * actor's own `TimerScheduler` sends it.
    */
  final case class TurnTimeout(playerId: PlayerId, forMoveCount: Int) extends Command[Nothing]

  /** The fallback the shared actor applies when a player's turn clock expires. Supplied per game type via the
    * `timeoutAction` seam: a game with a safe "no-op" move returns [[TimeoutAction.AutoMove]] (auto-played and recorded
    * like a normal move), while a game with no pass returns [[TimeoutAction.Forfeit]] (routed through
    * `Game.playerLeft`, handing two-player games to the opponent).
    *
    * @tparam M the game's move type; covariant so `Forfeit` fits any game's actor
    */
  sealed trait TimeoutAction[+M]
  object TimeoutAction {

    /** Auto-play `move` on behalf of the timed-out player (e.g. Pig auto-hold, Hold 'Em auto-check-or-fold). */
    final case class AutoMove[M](move: M) extends TimeoutAction[M]

    /** Forfeit the timed-out player via `Game.playerLeft`. */
    case object Forfeit extends TimeoutAction[Nothing]
  }

  /** Timer key for the single per-turn clock; re-arming with this key replaces any prior turn's timer. */
  private case object TurnTimerKey
}

/** The single [[GameActor]] implementation shared by every turn-based game.
  *
  * All game-specific rules live in the model: `game.play` validates and applies moves (including turn order), and
  * `game.playerFor` resolves a platform PlayerId to the game's player token. What remains — persistence, subscriber
  * fan-out, event publishing, and lifecycle — is identical across games and implemented once here.
  *
  * Adding a new game type therefore requires no actor code beyond a binding:
  * {{{
  *   object MyGameActor extends TurnBasedGameActor[MyGame, MyMove, MySeat, MyGameView](
  *     players => MyGame.empty(players(0), players(1)),
  *     deriveEncoder[MyMove]
  *   )
  * }}}
  * given a `GameTypeTag[MyGame]` (model) and a `GameProjection[MyGame, MyGameView]` (game package) instance.
  *
  * @param newGame factory producing the initial game model from the seated players; the player count has already been
  *                validated against the GameType's bounds by [[GameManager]]
  * @param moveEncoder serializes a move to JSON for the append-only history log
  * @tparam G the concrete game model type
  * @tparam M the game's move type
  * @tparam P the game's player-token (seat) type
  * @tparam S the per-viewer view type fanned out to subscribers and returned to callers
  * @see [[GameActor]] for the full actor lifecycle and behavioral contract.
  */
class TurnBasedGameActor[G <: Game[M, G, P, GameStatus[P], GameError], M, P, S <: GameView](
    newGame: Seq[PlayerId] => G,
    moveEncoder: Encoder[M]
)(implicit tag: GameTypeTag[G], view: GameProjection[G, S], ct: ClassTag[G])
    extends GameActor[G] {

  import TurnBasedGameActor._

  type Command = TurnBasedGameActor.Command[M]

  /** Per-game policy for an expired turn clock, given the current game: a safe auto-move or a forfeit. Defaults to
    * `TimeoutAction.Forfeit`, which suits every game with no "pass"; games with a safe no-op (Pig, Texas Hold 'Em)
    * override it. Only consulted when a turn clock is configured for this game type (see
    * `pekko-game-service.turn-timeouts`).
    */
  protected def timeoutAction(game: G): TimeoutAction[M] = TimeoutAction.Forfeit

  override def subscribeCommand(playerRef: ActorRef[PlayerActor.Command], playerId: PlayerId): GameActor.GameCommand =
    Subscribe(playerRef, playerId)

  override def unsubscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand =
    Unsubscribe(playerRef)

  override def broadcastCommand(event: PlayerEvent): GameActor.GameCommand =
    Broadcast(event)

  override def forfeitCommand(
      playerId: PlayerId,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): GameActor.GameCommand =
    PlayerLeft(playerId, replyTo)

  override protected def snapshotSavedResult(cmd: Command): Option[Either[Throwable, Unit]] = cmd match {
    case SnapshotSaved(result) => Some(result)
    case _                     => None
  }

  override protected def replyToInTerminating(cmd: Command): Option[ActorRef[Either[GameError, GameView]]] =
    cmd match {
      case MakeMove(_, _, replyTo) => Some(replyTo)
      case PlayerLeft(_, replyTo)  => Some(replyTo)
      case GetState(replyTo)       => Some(replyTo)
      case _                       => None
    }

  /** The turn clock configured for this game type, read from the actor system's config so a test can inject a short
    * (or absent) duration. `None` means no timer is ever armed — the original wait-forever behavior.
    */
  private def turnTimeoutFor(context: ActorContext[Command]): Option[FiniteDuration] =
    TurnTimeoutConfig.fromConfig(context.system.settings.config).forGameType(gameType)

  /** The platform id of the seat whose turn it is, resolved from the game's `currentPlayer` token. */
  private def currentPlayerId(game: G): Option[PlayerId] =
    game.players.find(pid => game.playerFor(pid).contains(game.currentPlayer))

  /** Arm (or re-arm) the single per-turn timer for the current player, keyed to `moveCount` so a stale fire is
    * ignored. A no-op when no clock is configured or the game is not in progress. Re-arming replaces any prior turn's
    * timer, so each move restarts the clock for the next player.
    */
  private def armTurnTimer(timers: TimerScheduler[Command], game: G, timeout: Option[FiniteDuration]): Unit =
    timeout.foreach { duration =>
      if (game.gameStatus == InProgress)
        currentPlayerId(game).foreach { pid =>
          timers.startSingleTimer(TurnTimerKey, TurnTimeout(pid, game.moveCount), duration)
        }
    }

  /** Initializes a new game actor with an empty game. */
  override def create(
      matchId: MatchId,
      roomId: RoomId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: EventPublisher
  ): (G, Behavior[Command]) = {
    val game = newGame(players)
    val behavior = Behaviors.setup[Command] { context =>
      context.log.info(s"[$matchId] starting new game")
      Behaviors.withTimers[Command] { timers =>
        val timeout = turnTimeoutFor(context)
        armTurnTimer(timers, game, timeout)
        active(game, matchId, roomId, persist, gameManager, Map.empty, publisher, timers, timeout)
      }
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
      matchId: MatchId,
      roomId: RoomId,
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
              context.log.info(s"[$matchId] restored in-progress game")
              Behaviors.withTimers[Command] { timers =>
                val timeout = turnTimeoutFor(context)
                armTurnTimer(timers, game, timeout)
                active(game, matchId, roomId, persist, gameManager, Map.empty, publisher, timers, timeout)
              }
            case Won(_) | Draw =>
              context.log.info(s"[$matchId] restored as already-completed game — notifying GameManager and stopping")
              gameManager ! GameManager.GameCompleted(matchId, roomId, GameLifecycleStatus.Completed)
              Behaviors.stopped
          }
        case _ =>
          context.log.error(s"Unexpected snapshot type for game $matchId: $snap")
          Behaviors.stopped
      }
    }

  /** Connected subscribers who are not one of `game.players` — i.e. watching but not seated in the match. */
  private def spectatorCount(subscribers: Map[ActorRef[PlayerActor.Command], PlayerId], game: G): Int =
    (subscribers.values.toSet -- game.players.toSet).size

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
      matchId: MatchId,
      roomId: RoomId,
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      subscribers: Map[ActorRef[PlayerActor.Command], PlayerId],
      publisher: EventPublisher,
      forfeit: Boolean,
      replyTo: ActorRef[Either[GameError, GameView]],
      timers: TimerScheduler[Command],
      timeout: Option[FiniteDuration]
  )(appendMove: => Unit = ()): Behavior[Command] = {
    context.log.info(s"Match $matchId updated:\n$nextState")

    persist ! PersistenceProtocol.SaveSnapshot(
      matchId = matchId,
      gameType = gameType,
      game = nextState,
      // pass the real save outcome through so a failed snapshot is logged by the SnapshotSaved handler
      replyTo = context.messageAdapter(saved => SnapshotSaved(saved.result))
    )

    appendMove

    // reply to the acting player with their own view; fan out a per-viewer view to each subscriber
    replyTo ! Right(view.fromGame(nextState, Some(viewerId)))
    val spectators = spectatorCount(subscribers, nextState)
    subscribers.foreach { case (ref, subscriberId) =>
      val event = PlayerEvent.GameStateUpdated(roomId, view.fromGame(nextState, Some(subscriberId)), spectators)
      ref ! PlayerActor.SendEvent(event)
    }

    nextState.gameStatus match {
      case Won(_) | Draw =>
        context.log.info(s"[$matchId] game completed with status: ${nextState.gameStatus}")
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
          // bots hold no account and no history: a human's win over a bot records, but the bot's own row never does
          if (!BotId.isBot(pid))
            persist ! PersistenceProtocol.RecordGameResult(pid, matchId, gameType, result, forfeit)
        }
        publisher.publish(GameEvent.GameCompleted(matchId, gameType, outcome, nextState.moveCount))
        // hand the subscriber set back to the room so it survives this match's actor stopping and can keep fanning out
        // chat and the post-game state; re-key by playerId to match the GameCompleted/MatchEnded shape. Bots are
        // dropped: they stop on the GameEnded above, so their refs must not linger as post-game subscribers — a dead
        // ref would keep an otherwise-empty room from being evicted and bounce post-game chat to a stopped actor.
        val subscribersByPlayer = subscribers.collect { case (ref, pid) if !BotId.isBot(pid) => pid -> ref }
        gameManager ! GameManager.GameCompleted(matchId, roomId, GameLifecycleStatus.Completed, subscribersByPlayer)
        // the game is over: cancel any pending turn clock so it cannot fire during terminating
        timers.cancel(TurnTimerKey)
        terminating(matchId)

      case InProgress =>
        // the turn advanced (or a multi-seat fold-out kept the game going): restart the clock for the new player
        armTurnTimer(timers, nextState, timeout)
        active(nextState, matchId, roomId, persist, gameManager, subscribers, publisher, timers, timeout)
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
      matchId: MatchId,
      roomId: RoomId,
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      subscribers: Map[ActorRef[PlayerActor.Command], PlayerId],
      publisher: EventPublisher,
      timers: TimerScheduler[Command],
      timeout: Option[FiniteDuration]
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
                  matchId,
                  roomId,
                  persist,
                  gameManager,
                  subscribers,
                  publisher,
                  forfeit = false,
                  replyTo,
                  timers,
                  timeout
                ) {
                  // record the move in the append-only history log; seq is the pre-move count (0-based ordinal)
                  persist ! PersistenceProtocol.AppendMove(matchId, game.moveCount, playerId, moveEncoder(move))
                  publisher.publish(GameEvent.MoveMade(matchId, gameType, playerId, game.moveCount))
                }

              case Left(err) =>
                context.log.warn(s"[$matchId] move rejected: $err")
                replyTo ! Left(err)
                Behaviors.same
            }

          case None =>
            context.log.warn(s"[$matchId] Player ID '$playerId' is not part of this game.")
            replyTo ! Left(GameError.InvalidPlayer(playerId))
            Behaviors.same
        }

      case PlayerLeft(playerId, replyTo) =>
        // forfeit/fold-out semantics live in the model; the resulting status drives completion exactly like a move
        game.playerLeft(playerId) match {
          case Right(nextState) =>
            context.log.info(s"[$matchId] player $playerId left the game")
            applyTransition(
              context,
              nextState,
              playerId,
              matchId,
              roomId,
              persist,
              gameManager,
              subscribers,
              publisher,
              forfeit = true,
              replyTo,
              timers,
              timeout
            )()

          case Left(err) =>
            context.log.warn(s"[$matchId] leave rejected: $err")
            replyTo ! Left(err)
            Behaviors.same
        }

      case TurnTimeout(playerId, forMoveCount) =>
        // stale-timer guard: only act if this fire still matches the current turn. A move already applied (advancing
        // moveCount, and usually currentPlayer) makes an in-flight timeout from the prior turn a no-op.
        val isCurrentTurn =
          game.gameStatus == InProgress && game.moveCount == forMoveCount && currentPlayerId(game).contains(playerId)
        if (!isCurrentTurn) {
          context.log.debug(s"[$matchId] stale turn timeout for $playerId@$forMoveCount ignored")
          Behaviors.same
        } else
          timeoutAction(game) match {
            case TimeoutAction.Forfeit =>
              context.log.info(s"[$matchId] turn timeout: $playerId forfeits")
              game.playerLeft(playerId) match {
                case Right(nextState) =>
                  applyTransition(
                    context,
                    nextState,
                    playerId,
                    matchId,
                    roomId,
                    persist,
                    gameManager,
                    subscribers,
                    publisher,
                    forfeit = true,
                    context.system.ignoreRef,
                    timers,
                    timeout
                  )()

                // unreachable: the isCurrentTurn guard already established InProgress and that playerId is the current
                // participant, so a game's playerLeft cannot reject this forfeit
                case Left(err) =>
                  context.log.warn(s"[$matchId] timeout forfeit rejected: $err")
                  Behaviors.same
              }

            case TimeoutAction.AutoMove(move) =>
              // isCurrentTurn guarantees currentPlayer is playerId's seat, so play on that token
              game.play(game.currentPlayer, move) match {
                case Right(nextState) =>
                  context.log.info(s"[$matchId] turn timeout: auto-move for $playerId")
                  applyTransition(
                    context,
                    nextState,
                    playerId,
                    matchId,
                    roomId,
                    persist,
                    gameManager,
                    subscribers,
                    publisher,
                    forfeit = false,
                    context.system.ignoreRef,
                    timers,
                    timeout
                  ) {
                    // an auto-move is a real move: record it in the history log and emit the analytics event
                    persist ! PersistenceProtocol.AppendMove(matchId, game.moveCount, playerId, moveEncoder(move))
                    publisher.publish(GameEvent.MoveMade(matchId, gameType, playerId, game.moveCount))
                  }

                case Left(err) =>
                  context.log.warn(s"[$matchId] timeout auto-move rejected: $err")
                  Behaviors.same
              }
          }

      case GetState(replyTo) =>
        replyTo ! Right(view.fromGame(game, None))
        Behaviors.same

      case Subscribe(playerRef, playerId) =>
        // watch the subscriber so its termination (disconnect/reconnect) drops it from the map automatically
        context.watch(playerRef)
        val updatedSubscribers = subscribers + (playerRef -> playerId)
        // only the (un)subscribing client is pushed here; already-connected viewers pick up the new spectator count
        // on the next real state change (a move) rather than being re-pushed a full board on every subscribe
        val viewState = view.fromGame(game, Some(playerId))
        val event = PlayerEvent.GameStateUpdated(roomId, viewState, spectatorCount(updatedSubscribers, game))
        playerRef ! PlayerActor.SendEvent(event)
        // a spectator (un)subscribing does not change whose turn it is, so the running turn clock is left untouched
        active(game, matchId, roomId, persist, gameManager, updatedSubscribers, publisher, timers, timeout)

      case Unsubscribe(playerRef) =>
        context.unwatch(playerRef)
        val updatedSubscribers = subscribers - playerRef
        active(game, matchId, roomId, persist, gameManager, updatedSubscribers, publisher, timers, timeout)

      case Broadcast(event) =>
        // chat and other broadcasts carry no hidden state, so every viewer gets the same event
        subscribers.foreach { case (ref, _) => ref ! PlayerActor.SendEvent(event) }
        Behaviors.same

      case SnapshotSaved(result) =>
        result match {
          case Left(e)  => context.log.error(s"[$matchId] snapshot failed", e)
          case Right(_) => context.log.debug(s"[$matchId] snapshot saved successfully")
        }
        Behaviors.same
    }
  }.receiveSignal { case (context, Terminated(ref)) =>
    // a subscribed PlayerActor stopped; drop its (now-dead) ref so we stop fanning events to it
    context.log.debug(s"[$matchId] subscriber $ref terminated; removing from subscribers")
    val remaining = subscribers.filterNot { case (r, _) => r == ref }
    active(game, matchId, roomId, persist, gameManager, remaining, publisher, timers, timeout)
  }
}
