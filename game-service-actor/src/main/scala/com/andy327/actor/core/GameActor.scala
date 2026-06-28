package com.andy327.actor.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}

import com.andy327.actor.events.EventPublisher
import com.andy327.actor.game.GameState
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{Game, GameError, GameType, GameTypeTag, MatchId, PlayerId, RoomId}

object GameActor {

  /** Super-type for all concrete commands that a game-specific actor understands.
    *
    * Every game actor defines its own sealed ADT that extends this trait so GameManager can treat them uniformly when
    * forwarding messages.
    */
  trait GameCommand
}

/** Behavioral contract for actors that manage a single game instance from creation to completion.
  *
  * The standard lifecycle is:
  *   - `create` spawns a fresh actor in the `active` behavior with an empty game
  *   - `fromSnapshot` re-hydrates an actor from a persisted game; if the snapshot is already terminal the actor
  *     notifies [[GameManager]] and stops immediately without entering `active`
  *   - In `active`, each `MakeMove` validates the move, applies it, saves a snapshot via fire-and-forget
  *     [[com.andy327.actor.persistence.PersistenceProtocol.SaveSnapshot]], fans out
  *     [[com.andy327.actor.core.PlayerEvent.GameStateUpdated]] to all subscribers, and replies to the caller
  *   - On a terminal game result (win or draw), the actor fans out
  *     [[com.andy327.actor.core.PlayerEvent.GameEnded]], notifies
  *     [[GameManager]] via `GameCompleted`, and transitions to `terminating`
  *   - In `terminating`, all commands except `SnapshotSaved` are ignored; the actor self-stops once the final snapshot
  *     is confirmed, so [[GameManager]] does not need to call `context.stop`
  *
  * [[TurnBasedGameActor]] implements this contract once for all turn-based games; per-game objects (e.g.
  * `TicTacToeActor`) are thin bindings supplying the model's factory and type-class instances.
  *
  * @tparam G the concrete game model type this actor manages
  */
trait GameActor[G <: Game[_, _, _, _, _]] {

  type Command <: GameActor.GameCommand

  /** Resolves to the compile-time `GameType` matching `G` via an implicit tag. */
  def gameType(implicit tag: GameTypeTag[G]): GameType = tag.value

  /** Spawn a fresh game actor for a newly created game.
    *
    * @param matchId the unique identifier for this match; keys its snapshot, move log, results, and analytics
    * @param roomId the room hosting the match; carried so push events and completion can be addressed to the room
    * @param players the ordered list of players; game type determines how many are required
    * @param persist the shared persistence actor used to save snapshots
    * @param gameManager the parent GameManager actor, used to report game-end events
    * @param publisher emit seam for analytics events (game started/move made/game completed)
    * @return the initial game model and a `Behavior` ready to receive commands
    */
  def create(
      matchId: MatchId,
      roomId: RoomId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: EventPublisher
  ): (G, Behavior[Command])

  /** Re-hydrate an actor from a snapshot already loaded from the database.
    *
    * @param matchId the unique identifier for the match being restored
    * @param roomId the room hosting the match; carried so push events and completion can be addressed to the room
    * @param snap the snapshot returned by the persistence layer; must be a valid `G`
    * @param persist the shared persistence actor used to save future snapshots
    * @param gameManager the parent GameManager actor
    * @param publisher emit seam for analytics events (game started/move made/game completed)
    * @return a `Behavior` initialised from the snapshot state
    */
  def fromSnapshot(
      matchId: MatchId,
      roomId: RoomId,
      snap: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: EventPublisher
  ): Behavior[Command]

  /** Produce the game-specific Subscribe command that registers `playerRef` (the session for `playerId`) for push
    * events. The `playerId` lets the actor render each subscriber's own view — full-information games ignore it,
    * hidden-state games use it for per-viewer serialization.
    */
  def subscribeCommand(playerRef: ActorRef[PlayerActor.Command], playerId: PlayerId): GameActor.GameCommand

  /** Produce the game-specific command that deregisters `playerRef` from this game's push events. Lets GameManager
    * unsubscribe a spectator without knowing the game's concrete command type. A no-op for a ref that is not
    * subscribed.
    */
  def unsubscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand

  /** Produce the game-specific command that fans `event` out to all of the game's subscribers (e.g. a chat message). */
  def broadcastCommand(event: PlayerEvent): GameActor.GameCommand

  /** Produce the game-specific command that applies `playerId` leaving the game (a forfeit for two-player games),
    * replying to `replyTo` with the leaver's view of the resulting state or a `GameError` if the leave is rejected.
    * Lets GameManager trigger a forfeit without knowing the game's concrete move/command types.
    */
  def forfeitCommand(playerId: PlayerId, replyTo: ActorRef[Either[GameError, GameState]]): GameActor.GameCommand

  /** Extract the snapshot-save result from `cmd` if it is a `SnapshotSaved` message, otherwise `None`.
    *
    * Used by [[terminating]] to detect when the final snapshot has been confirmed without needing to know the
    * concrete `SnapshotSaved` type defined in each actor's sealed `Command` ADT.
    */
  protected def snapshotSavedResult(cmd: Command): Option[Either[Throwable, Unit]]

  /** Extract the `replyTo` ref from `cmd` if it is a command that expects a `GameError`/`GameState` reply (a move,
    * a leave, or a state read), otherwise `None`.
    *
    * Used by [[terminating]] to answer a request that lands in the brief window between game completion and the
    * final snapshot being confirmed, rather than leaving the caller's `ask` to time out silently.
    */
  protected def replyToInTerminating(cmd: Command): Option[ActorRef[Either[GameError, GameState]]]

  /** Waits for the final snapshot confirmation after a game ends, then stops the actor.
    *
    * Entered after a win or draw is detected. A move/leave/state-read landing in this window is answered with
    * `GameError.GameOver` rather than dropped, so the caller's `ask` doesn't time out; everything else
    * (subscribe/unsubscribe/broadcast) is ignored — the actor is shutting down and should not take on new subscribers.
    * The actor self-stops once the final snapshot is confirmed, so [[com.andy327.actor.core.GameManager]] does not need
    * to call `context.stop`.
    *
    * A `Terminated` signal from a still-watched subscriber is ignored here; without an explicit handler Pekko would
    * raise `DeathPactException` and crash the actor before the final snapshot is confirmed.
    */
  protected def terminating(matchId: MatchId): Behavior[Command] =
    Behaviors.receive[Command] { (context, msg) =>
      snapshotSavedResult(msg) match {
        case Some(result) =>
          result match {
            case Left(e)  => context.log.error(s"[$matchId] final snapshot failed", e)
            case Right(_) => context.log.debug(s"[$matchId] final snapshot saved, stopping")
          }
          Behaviors.stopped
        case None =>
          replyToInTerminating(msg).foreach(_ ! Left(GameError.GameOver))
          Behaviors.same
      }
    }.receiveSignal { case (_, Terminated(_)) =>
      Behaviors.same
    }
}
