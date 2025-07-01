package com.andy327.persistence.db

import cats.effect.IO

import com.andy327.model.core.{Game, GameId, GameType}

/**
 * Trait representing a repository interface for saving and loading game state from a persistent database.
 *
 * This is a generic, type-erased abstraction designed to work across multiple game types. Concrete implementations are
 * responsible for encoding/decoding the actual game instances
 */
trait GameRepository {

  /** Runs any needed initialization processes such as creating the table in the database. */
  def initialize(): IO[Unit]

  /** Persist the current state of a game to the database. */
  def saveGame(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]

  /** LLoad a game from the database using its ID and GameType. */
  def loadGame(gameId: GameId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]]

  /**
   * Load all saved games from the database.
   *
   * This may be used during server startup to rehydrate all active games into memory.
   */
  def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]]
}
