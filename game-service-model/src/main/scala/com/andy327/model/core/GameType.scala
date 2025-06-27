package com.andy327.model.core

sealed trait GameType {
  def minPlayers: Int
  def maxPlayers: Int
}

object GameType {
  case object TicTacToe extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }

  /**
   * Parses a string into a GameType. Returns Some(GameType) if the input matches a known type, or None if it's invalid.
   */
  def fromString(s: String): Option[GameType] = s.toLowerCase match {
    case "tictactoe" => Some(TicTacToe)
    case _           => None
  }
}
