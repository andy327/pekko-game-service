package com.andy327.server.http.json

import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.{Draw, Game, GameStatus, Mark, PlayerId, Won}
import com.andy327.model.tictactoe.TicTacToe

/** Super-type for all serializable “view” representations of game state that can be sent to the client as part of an
  * HTTP response.
  */
sealed trait GameState

/** Serializable view of any grid-based game state, suitable for HTTP responses.
  *
  * Shared by every game whose state is a board of cells each holding an optional mark: cells carry the mark's symbol
  * or `""` when empty, and the current player and winner are identified by their mark symbols.
  */
case class GridGameState(
    board: Vector[Vector[String]],
    currentPlayer: String,
    winner: Option[String],
    draw: Boolean
) extends GameState

object GridGameState {

  /** Builds the view from any board of optional marks plus the game's current player and status. */
  def of(board: Vector[Vector[Option[Mark]]], currentPlayer: Mark, status: GameStatus[Mark]): GridGameState = {
    val cells = board.map(_.map(_.map(_.toString).getOrElse(""))) // string-based representation for JSON
    val winner = status match {
      case Won(mark) => Some(mark.toString)
      case _         => None
    }
    GridGameState(cells, currentPlayer.toString, winner, status == Draw)
  }
}

/** Type class for converting an internal game model into a serializable GameState.
  *
  * Instances live in the companion object, which is always in implicit scope for `GameStateView[G, S]` searches, so
  * call sites need no imports beyond the types themselves.
  *
  * `viewer` identifies who the view is being rendered for: `Some(playerId)` for that player's own view, or `None` for a
  * public/spectator view. Full-information games (TicTacToe, ConnectFour) show everyone the same board and ignore it;
  * hidden-state games (e.g. Battleship) use it to reveal only what that viewer is allowed to see.
  */
trait GameStateView[G <: Game[_, _, _, _, _], S <: GameState] {
  def fromGame(game: G, viewer: Option[PlayerId]): S
}

object GameStateView {

  /** Type class instance for serializing a TicTacToe game into a GridGameState (same board for every viewer). */
  implicit val ticTacToeView: GameStateView[TicTacToe, GridGameState] =
    (game, _) => GridGameState.of(game.board, game.currentPlayer, game.gameStatus)

  /** Type class instance for serializing a ConnectFour game into a GridGameState (same board for every viewer). */
  implicit val connectFourView: GameStateView[ConnectFour, GridGameState] =
    (game, _) => GridGameState.of(game.board, game.currentPlayer, game.gameStatus)
}

/** Utility for converting internal game models to HTTP-serializable GameState representations using the GameStateView
  * type class.
  */
object GameStateConverters {

  /** Converts a concrete game instance into a corresponding GameState, rendered for `viewer`.
    *
    * This uses a type class instance of GameStateView[G, S], which knows how to convert a specific game type G (e.g.,
    * TicTacToe) into a specific GameState type S (e.g., GridGameState). `viewer` is `Some(playerId)` for that player's
    * own view or `None` for a public/spectator view; full-information games ignore it.
    */
  def serializeGame[G <: Game[_, _, _, _, _], S <: GameState](game: G, viewer: Option[PlayerId])(implicit
      view: GameStateView[G, S]
  ): S =
    view.fromGame(game, viewer)
}
