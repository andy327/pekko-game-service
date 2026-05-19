package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameId, GameType, GameTypeTag, PlayerId}
import com.andy327.server.actors.persistence.PersistenceProtocol

object GameActor {

  /** Super-type for all concrete commands that a game-specific actor understands.
    *
    * Every game actor defines its own sealed ADT that extends this trait so GameManager can treat them uniformly when
    * forwarding messages.
    */
  trait GameCommand
}

/** Type-class factory and helper interface implemented by every game-specific actor. */
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
    * @return the initial game model and a `Behavior` ready to receive commands
    */
  def create(
      gameId: GameId,
      players: Seq[PlayerId],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): (G, Behavior[Command])

  /** Re-hydrate an actor from a snapshot already loaded from the database.
    *
    * @param gameId the unique identifier for the game being restored
    * @param snap the snapshot returned by the persistence layer; must be a valid `G`
    * @param persist the shared persistence actor used to save future snapshots
    * @param gameManager the parent GameManager actor
    * @return a `Behavior` initialised from the snapshot state
    */
  def fromSnapshot(
      gameId: GameId,
      snap: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command],
      gameManager: ActorRef[GameManager.Command]
  ): Behavior[Command]

  /** Produce the game-specific Subscribe command that registers `playerRef` for push events. */
  def subscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand
}
