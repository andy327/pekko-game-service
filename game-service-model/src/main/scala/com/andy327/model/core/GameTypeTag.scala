package com.andy327.model.core

import com.andy327.model.battleship.Battleship
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.liarsdice.LiarsDice
import com.andy327.model.mastermind.Mastermind
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.TicTacToe

/** Type class used to associate a specific game model type (e.g., TicTacToe) with its corresponding GameType (e.g.,
  * GameType.TicTacToe).
  *
  * This allows us to infer the correct GameType based on the concrete game model type without requiring the user to
  * manually specify it. When adding a new Game, add a corresponding GameTypeTag implicit class.
  *
  * To support a new game type, define an implicit instance of GameTypeTag for that game's model class.
  *
  * @tparam G the game model type (e.g., TicTacToe)
  */
trait GameTypeTag[G] {
  def value: GameType
}

object GameTypeTag {
  def apply[G](implicit tag: GameTypeTag[G]): GameTypeTag[G] = tag

  implicit val ticTacToeTag: GameTypeTag[TicTacToe] = new GameTypeTag[TicTacToe] {
    override val value: GameType = GameType.TicTacToe
  }

  implicit val connectFourTag: GameTypeTag[ConnectFour] = new GameTypeTag[ConnectFour] {
    override val value: GameType = GameType.ConnectFour
  }

  implicit val battleshipTag: GameTypeTag[Battleship] = new GameTypeTag[Battleship] {
    override val value: GameType = GameType.Battleship
  }

  implicit val pigTag: GameTypeTag[Pig] = new GameTypeTag[Pig] {
    override val value: GameType = GameType.Pig
  }

  implicit val mastermindTag: GameTypeTag[Mastermind] = new GameTypeTag[Mastermind] {
    override val value: GameType = GameType.Mastermind
  }

  implicit val liarsDiceTag: GameTypeTag[LiarsDice] = new GameTypeTag[LiarsDice] {
    override val value: GameType = GameType.LiarsDice
  }
}
