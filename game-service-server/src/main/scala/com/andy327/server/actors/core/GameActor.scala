package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameId, GameType, GameTypeTag, PlayerId}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.pubsub.GameEventPublisher

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
  *     [[com.andy327.server.actors.persistence.PersistenceProtocol.SaveSnapshot]], fans out
  *     [[com.andy327.server.actors.core.PlayerEvent.GameStateUpdated]] to all subscribers, and replies to the caller
  *   - On a terminal game result (win or draw), the actor fans out
  *     [[com.andy327.server.actors.core.PlayerEvent.GameEnded]], notifies
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
    * @param gameId the unique identifier for the game
    * @param players the ordered list of players; game type determines how many are required
    * @param persist the shared persistence actor used to save snapshots
    * @param gameManager the parent GameManager actor, used to report game-end events
    * @param publisher used to publish events to Redis so other instances can relay them to remote players
    * @return the initial game model and a `Behavior` ready to receive commands
    */
  def create(
      gameId: GameId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: GameEventPublisher
  ): (G, Behavior[Command])

  /** Re-hydrate an actor from a snapshot already loaded from the database.
    *
    * @param gameId the unique identifier for the game being restored
    * @param snap the snapshot returned by the persistence layer; must be a valid `G`
    * @param persist the shared persistence actor used to save future snapshots
    * @param gameManager the parent GameManager actor
    * @param publisher used to publish events to Redis so other instances can relay them to remote players
    * @return a `Behavior` initialised from the snapshot state
    */
  def fromSnapshot(
      gameId: GameId,
      snap: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command],
      publisher: GameEventPublisher
  ): Behavior[Command]

  /** Produce the game-specific Subscribe command that registers `playerRef` for push events. */
  def subscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand

  /** Extract the snapshot-save result from `cmd` if it is a `SnapshotSaved` message, otherwise `None`.
    *
    * Used by [[terminating]] to detect when the final snapshot has been confirmed without needing to know the
    * concrete `SnapshotSaved` type defined in each actor's sealed `Command` ADT.
    */
  protected def snapshotSavedResult(cmd: Command): Option[Either[Throwable, Unit]]

  /** Waits for the final snapshot confirmation after a game ends, then stops the actor.
    *
    * Entered after a win or draw is detected. All commands except `SnapshotSaved` are ignored — the actor is
    * shutting down and should not process new moves or subscribe requests. The actor self-stops once the final
    * snapshot is confirmed, so [[com.andy327.server.actors.core.GameManager]] does not need to call `context.stop`.
    */
  protected def terminating(gameId: GameId): Behavior[Command] =
    Behaviors.receive { (context, msg) =>
      snapshotSavedResult(msg) match {
        case Some(result) =>
          result match {
            case Left(e)  => context.log.error(s"[$gameId] final snapshot failed", e)
            case Right(_) => context.log.debug(s"[$gameId] final snapshot saved, stopping")
          }
          Behaviors.stopped
        case None =>
          Behaviors.same
      }
    }
}
