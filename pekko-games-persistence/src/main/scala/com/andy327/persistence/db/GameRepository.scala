package com.andy327.persistence.db

import cats.effect.IO

import com.andy327.model.core.Game
import schema.GameType

trait GameRepository {
  def saveGame(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]
  def loadGame(gameId: String, gameType: GameType): IO[Option[Game[_, _, _, _, _]]]

  /** Load all games saved in the database */
  def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]]
}
