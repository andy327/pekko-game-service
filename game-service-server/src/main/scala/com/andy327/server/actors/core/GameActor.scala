package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameType, GameTypeTag}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.GameState

object GameActor {

  /**
   * Super-type for all concrete commands that a game-specific actor understands.
   *
   * Every game actor defines its own sealed ADT that extends this trait so GameManager can treat them uniformly when
   * forwarding messages.
   */
  trait GameCommand
}

/**
 * Type-class factory and helper interface implemented by every game-specific actor.
 */
trait GameActor[G <: Game[_, _, _, _, _]] {

  /** Resolves to the compile-time `GameType` matching `G` via an implicit tag. */
  def gameType(implicit tag: GameTypeTag[G]): GameType = tag.value

  /** Convert the fully-typed game model into an HTTP friendly payload. */
  def serializableGameState(game: G): GameState

  /** Spawn a fresh game actor given players, ids, etc. */
  def create(gameId: String, players: Seq[String], persist: ActorRef[PersistenceProtocol.Command]): (G, Behavior[_])

  /** Re-hydrate an actor from a snapshot already loaded from the database. */
  def fromSnapshot(
      gameId: String,
      snap: Game[_, _, _, _, _],
      persist: ActorRef[PersistenceProtocol.Command]
  ): Behavior[_]
}
