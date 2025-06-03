package com.andy327.server.db

import com.andy327.model.tictactoe.TicTacToe

import cats.effect.IO

trait GameRepository {
  def saveGame(gameId: String, game: TicTacToe): IO[Unit]
  def loadGame(gameId: String): IO[Option[TicTacToe]]

  /** Load all games saved in the database */
  def loadAllGames(): IO[Map[String, TicTacToe]]
}
