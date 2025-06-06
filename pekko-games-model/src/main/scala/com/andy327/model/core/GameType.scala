package com.andy327.model.core

sealed trait GameType
object GameType {
  case object TicTacToe extends GameType
}
