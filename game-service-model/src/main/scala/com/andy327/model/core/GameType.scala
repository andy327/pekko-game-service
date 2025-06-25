package com.andy327.model.core

sealed trait GameType {
  def minPlayers: Int
  def maxPlayers: Int
}

object GameType {
  case object TicTacToe extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }
}
