package com.andy327.model.core

/**
 * Represents a type of game supported by the system.
 *
 * Each GameType defines the number of players required to play that game, allowing the game manager to enforce lobby
 * constraints and initialization rules.
 */
sealed trait GameType {

  /** Minimum number of players required to start this game type. */
  def minPlayers: Int

  /** Maximum number of players allowed in this game type. */
  def maxPlayers: Int
}

object GameType {

  /**
   * A classic two-player game of Tic-tac-toe where players alternate marking a 3x3 board.
   */
  case object TicTacToe extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }

  /**
   * Parses a string into a GameType instance.
   *
   * @param s the name of the game type (case-insensitive)
   * @return Some(GameType) if recognized, or None if the input is invalid
   */
  def fromString(s: String): Option[GameType] = s.toLowerCase match {
    case "tictactoe" => Some(TicTacToe)
    case _           => None
  }
}
