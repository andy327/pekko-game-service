package com.andy327.server.db

import cats.effect.IO

import com.andy327.model.tictactoe.TicTacToe

trait GameRepository {
  def saveGame(gameId: String, game: TicTacToe): IO[Unit]
  def loadGame(gameId: String): IO[Option[TicTacToe]]

  /** Load all games saved in the database */
  def loadAllGames(): IO[Map[String, TicTacToe]]
}
