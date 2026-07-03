package com.andy327.model.core

/** Represents a type of game supported by the system.
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

  /** A classic two-player game of Tic-tac-toe where players alternate marking a 3x3 board. */
  case object TicTacToe extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }

  /** A two-player strategy game where players drop pieces into a 6-row x 7-column grid, aiming to connect four. */
  case object ConnectFour extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }

  /** A two-player hidden-information game where players fire at each other's concealed fleets until one is sunk. */
  case object Battleship extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }

  /** A press-your-luck dice game for 2–8 players: roll to accumulate points, but a 1 busts your turn. */
  case object Pig extends GameType {
    val (minPlayers, maxPlayers) = (2, 8)
  }

  /** A two-player code-breaking game: one player sets a hidden color code, the other guesses it from peg feedback. */
  case object Mastermind extends GameType {
    val (minPlayers, maxPlayers) = (2, 2)
  }

  /** A 2–6 player dice-bluffing game: players bid on the dice hidden under every cup until someone calls "Liar". */
  case object LiarsDice extends GameType {
    val (minPlayers, maxPlayers) = (2, 6)
  }

  /** Parses a string into a GameType instance.
    *
    * @param s the name of the game type (case-insensitive)
    * @return Some(GameType) if recognized, or None if the input is invalid
    */
  def fromString(s: String): Option[GameType] = s.toLowerCase match {
    case "tictactoe"   => Some(TicTacToe)
    case "connectfour" => Some(ConnectFour)
    case "battleship"  => Some(Battleship)
    case "pig"         => Some(Pig)
    case "mastermind"  => Some(Mastermind)
    case "liarsdice"   => Some(LiarsDice)
    case _             => None
  }
}
