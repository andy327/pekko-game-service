package com.andy327.model.core

import com.andy327.model.tictactoe.TicTacToe

/**
 * Type class used to associate a specific game model type (e.g., TicTacToe) with its corresponding GameType (e.g.,
 * GameType.TicTacToe).
 *
 * This allows us to infer the correct GameType based on the concrete game model type without requiring the user to
 * manually specify it. When adding a new Game, add a corresponding GameTypeTag implicit class.
 */
trait GameTypeTag[G] {
  def value: GameType
}

object GameTypeTag {
  def apply[G](implicit tag: GameTypeTag[G]): GameTypeTag[G] = tag

  implicit val ticTacToeTag: GameTypeTag[TicTacToe] = new GameTypeTag[TicTacToe] {
    override val value: GameType = GameType.TicTacToe
  }
}
