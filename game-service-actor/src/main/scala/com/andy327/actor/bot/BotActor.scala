package com.andy327.actor.bot

import scala.concurrent.duration._
import scala.util.Random

import org.apache.pekko.actor.typed.scaladsl.{Behaviors, TimerScheduler}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.actor.core.{GameManager, PlayerActor, PlayerEvent}
import com.andy327.actor.game.{GameOperation, MovePayload}
import com.andy327.model.core.{PlayerId, RoomId}

/** The session that stands in for a bot player in a running game.
  *
  * A bot holds no WebSocket, but from the game actor's side it is just another subscriber: it is registered with the
  * game's own `PlayerActor` `Subscribe` command through a session adapter, and thereafter receives the same per-viewer
  * push events a human client would. When a push carries a state in which the bot has a move to make — which its
  * [[AiPolicy]] signals by returning `Some`, since a view lists only its own viewer's legal moves — the bot pauses for
  * a short "thinking" beat and then submits the chosen move through the same `GameManager.RunGameOperation` path a
  * human move takes. The pause both makes play feel natural and keeps a bot-versus-bot game from resolving in a tight
  * loop.
  *
  * The bot decides only from the view it is pushed, so it cannot act on information a human in its seat would not have;
  * and because the game is turn-based, exactly one push per turn hands it the move, so at most one submission is in
  * flight at a time (tracked as the `pending` move). A terminal `GameEnded` push stops the actor — a rematch spawns a
  * fresh bot rather than reusing this one.
  *
  * Actor relationships:
  *   - Parent: `GameManager` (spawns one per bot seat at game start and on restore)
  *   - Receives from: the game actor (push events, via the session adapter), its own turn-delay timer
  *   - Sends to: `GameManager` (`RunGameOperation` to submit a move)
  */
object BotActor {

  /** How long a bot "thinks" before submitting, unless overridden. Long enough to feel deliberate and to keep a
    * bot-versus-bot game from spinning, short enough not to try a waiting human's patience.
    */
  val DefaultThinkDelay: FiniteDuration = 700.millis

  sealed trait Command

  /** A push event delivered to the bot's session adapter by the game actor. */
  final private case class WrappedEvent(event: PlayerEvent) extends Command

  /** The turn-delay timer has elapsed: submit `move`, decided when the turn was recognized. */
  final private case class Act(move: MovePayload) extends Command

  /** The game ended (or the session was closed): stop. */
  private case object Stop extends Command

  /** The reply to a submitted move, adapted from `GameManager.GameResponse`; observed only to log a rejection. */
  final private case class MoveResult(response: GameManager.GameResponse) extends Command

  /** Timer key for the single turn-delay timer; re-arming replaces any prior turn's pending act. */
  private case object ActKey

  /** Creates a bot session for `botId` in `roomId`, deciding with `policy` and submitting through `gameManager`.
    *
    * @param rng source of randomness the policy draws from; seed it for reproducible play
    * @param thinkDelay pause between recognizing a turn and submitting; `Duration.Zero` submits immediately (used by
    *                   tests for determinism)
    * @param register called once on start with the bot's session adapter, so the caller — which holds the game actor
    *                 ref — can subscribe it; keeps this actor free of the game-actor command type
    */
  def apply(
      botId: PlayerId,
      roomId: RoomId,
      policy: AiPolicy,
      gameManager: ActorRef[GameManager.Command],
      rng: Random = new Random,
      thinkDelay: FiniteDuration = DefaultThinkDelay
  )(register: ActorRef[PlayerActor.Command] => Unit): Behavior[Command] =
    Behaviors.setup { context =>
      val session = context.messageAdapter[PlayerActor.Command] {
        case PlayerActor.SendEvent(event) => WrappedEvent(event)
        case PlayerActor.Disconnect       => Stop
      }
      register(session)
      Behaviors.withTimers { timers =>
        active(botId, roomId, policy, gameManager, rng, thinkDelay, timers, pending = false)
      }
    }

  private def active(
      botId: PlayerId,
      roomId: RoomId,
      policy: AiPolicy,
      gameManager: ActorRef[GameManager.Command],
      rng: Random,
      thinkDelay: FiniteDuration,
      timers: TimerScheduler[Command],
      pending: Boolean
  ): Behavior[Command] = Behaviors.receive { (context, message) =>
    def loop(pending: Boolean): Behavior[Command] =
      active(botId, roomId, policy, gameManager, rng, thinkDelay, timers, pending)

    message match {
      case WrappedEvent(PlayerEvent.GameStateUpdated(_, view, _)) =>
        // a fresh turn hands us exactly one push; ignore further pushes while a move is already scheduled
        if (pending) Behaviors.same
        else
          policy.decide(view, rng) match {
            case Some(move) =>
              if (thinkDelay <= Duration.Zero) context.self ! Act(move)
              else timers.startSingleTimer(ActKey, Act(move), thinkDelay)
              loop(pending = true)
            case None => Behaviors.same // not our turn, or nothing to play
          }

      case WrappedEvent(PlayerEvent.GameEnded(_)) =>
        context.log.debug(s"[$roomId] bot $botId sees the game ended; stopping")
        Behaviors.stopped

      case WrappedEvent(_) =>
        Behaviors.same // chat and other events are not the bot's concern

      case Act(move) =>
        val adapter = context.messageAdapter[GameManager.GameResponse](MoveResult(_))
        gameManager ! GameManager.RunGameOperation(roomId, GameOperation.MakeMove(botId, move), adapter)
        loop(pending = false)

      case MoveResult(GameManager.MoveRejected(reason)) =>
        // the legality tests make this unreachable in practice; if it ever fires, a policy produced an illegal move
        context.log.warn(s"[$roomId] bot $botId move rejected: $reason")
        Behaviors.same

      case MoveResult(_) =>
        Behaviors.same // the accepted state arrives as a GameStateUpdated push, so the reply itself is unused

      case Stop =>
        Behaviors.stopped
    }
  }
}
