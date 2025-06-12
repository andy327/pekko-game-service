package com.andy327.server.http.json

import com.andy327.model.core.Game
import com.andy327.model.tictactoe._

/**
 * Super-type for all serializable “view” representations of game state that can be sent to the client as part of an
 * HTTP response.
 */
sealed trait GameState

/** Serializable view of a TicTacToe game state, suitable for HTTP responses. */
case class TicTacToeState(
    board: Vector[Vector[String]],
    currentPlayer: String,
    winner: Option[String],
    draw: Boolean
) extends GameState

object TicTacToeState {

  /** Type class instance for serializing a TicTacToe game into a TicTacToeState. */
  implicit object TicTacToeView extends GameStateView[TicTacToe, TicTacToeState] {
    def fromGame(game: TicTacToe): TicTacToeState = {
      val boardStrings = game.board.map(_.map(_.map(_.toString).getOrElse(""))) // string-based representation for JSON
      val currentPlayer = game.currentPlayer.toString
      val winnerOpt = game.gameStatus match {
        case Won(mark) => Some(mark.toString)
        case _         => None
      }
      val draw = game.gameStatus == Draw
      TicTacToeState(boardStrings, currentPlayer, winnerOpt, draw)
    }
  }
}

/**
 * Type class for converting an internal game model into a serializable GameState.
 *
 * To enable serialization via `serializeGame`, you must define and import an instance of this type class for your game
 * and GameState types.
 */
trait GameStateView[G <: Game[_, _, _, _, _], S <: GameState] {
  def fromGame(game: G): S
}

/**
 * Utility for converting internal game models to HTTP-serializable GameState representations using the GameStateView
 * type class.
 */
object GameStateConverters {

  /**
   * Converts a concrete game instance into a corresponding GameState.
   *
   * This uses a type class instance of GameStateView[G, S], which knows how to convert a specific game type G (e.g.,
   * TicTacToe) into a specific GameState type S (e.g., TicTacToeState).
   */
  def serializeGame[G <: Game[_, _, _, _, _], S <: GameState](game: G)(implicit view: GameStateView[G, S]): S =
    view.fromGame(game)
}
