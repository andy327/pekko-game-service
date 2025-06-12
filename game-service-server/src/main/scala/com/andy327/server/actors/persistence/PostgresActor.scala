package com.andy327.server.actors.persistence

import cats.effect.IO

import org.apache.pekko.actor.typed.Behavior

import com.andy327.model.core.{Game, GameType}
import com.andy327.persistence.db.GameRepository

/**
  * Concrete persistence-actor that stores snapshots in PostgreSQL via a GameRepository.
  */
object PostgresActor {
  def apply(gameRepo: GameRepository): Behavior[PersistenceProtocol.Command] =
    new Impl(gameRepo).behavior

  private class Impl(gameRepo: GameRepository) extends PersistActor {
    def loadFromStore(gameId: String, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] =
      gameRepo.loadGame(gameId, gameType)

    def saveToStore(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] =
      gameRepo.saveGame(gameId, gameType, game)
  }
}
