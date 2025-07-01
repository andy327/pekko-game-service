package com.andy327.model.tictactoe

import com.andy327.model.core.{Game, PlayerId, Renderable}

import GameError._

object TicTacToe {

  /** Creates a new empty TicTacToe game with the specified players. */
  def empty(playerX: PlayerId, playerO: PlayerId): TicTacToe = TicTacToe(
    playerX = playerX,
    playerO = playerO,
    board = Vector.fill(3, 3)(Option.empty[Mark]),
    currentPlayer = X,
    winner = None,
    isDraw = false
  )

  /** Returns true if all cells on the board are filled, and thus no more moves can be made. */
  private def isFull(board: Board): Boolean =
    board.flatten.forall(_.isDefined)

  /**
   * Checks if there is a winning line (row, column, or diagonal) on the board. If found, returns the winning Mark
   * (X or O).
   */
  private def checkWinner(board: Board): Option[Mark] = {
    val rows = board
    val cols = (0 until 3).map(i => board.map(_(i)))
    val diag1 = (0 to 2).map(i => board(i)(i))
    val diag2 = (0 to 2).map(i => board(i)(2 - i))

    (rows ++ cols :+ diag1 :+ diag2)
      .find(line => line.forall(_.isDefined) && line.distinct.size == 1)
      .flatMap(_.head)
  }
}

/**
 * Represents an instance of a TicTacToe game, including both the current state (players, board, turn, outcome) and the
 * rules for how the game evolves.
 *
 * This class is responsible for validating and applying player moves, determining when the game ends (via win or draw),
 * and updating game state accordingly.
 *
 * @param playerX the player id (uuid) assigned to X
 * @param playerO the player id (uuid) assigned to O
 * @param board the 3x3 board of Marks (Some(X), Some(O), or None)
 * @param currentPlayer the player (Mark) whose turn it is
 * @param winner the winning player's Mark, if the game is over
 * @param isDraw true if the game ended in a draw
 */
final case class TicTacToe(
    playerX: PlayerId,
    playerO: PlayerId,
    board: Board,
    currentPlayer: Mark,
    winner: Option[Mark],
    isDraw: Boolean
) extends Game[Location, TicTacToe, Mark, GameStatus, GameError]
    with Renderable {

  def currentState: TicTacToe = this

  def gameStatus: GameStatus =
    winner.map(Won).getOrElse(if (isDraw) Draw else InProgress)

  private def nextPlayer: Mark = currentPlayer match {
    case X => O
    case O => X
  }

  /**
   * Applies a move to the board for the current player.
   *
   * @param player the player making the move
   * @param loc the location on the board to place the mark
   * @return the updated game state, or a GameError if the move is invalid
   */
  def play(player: Mark, loc: Location): Either[GameError, TicTacToe] =
    if (gameStatus != InProgress)
      Left(GameOver)
    else if (player != currentPlayer)
      Left(InvalidTurn)
    else if (loc.row < 0 || loc.row > 2 || loc.col < 0 || loc.col > 2)
      Left(OutOfBounds)
    else if (board(loc.row)(loc.col).isDefined)
      Left(CellOccupied)
    else {
      val updatedBoard = board.updated(
        loc.row,
        board(loc.row).updated(loc.col, Some(currentPlayer))
      )

      val maybeWinner = TicTacToe.checkWinner(updatedBoard)
      val draw = maybeWinner.isEmpty && TicTacToe.isFull(updatedBoard)

      Right(
        copy(
          board = updatedBoard,
          currentPlayer = nextPlayer,
          winner = maybeWinner,
          isDraw = draw
        )
      )
    }

  def render: String = {
    val border = "-" * 7
    val rows = board.map { row =>
      row.map {
        case Some(mark) => mark.toString
        case None       => " "
      }.mkString("|", "|", "|")
    }.mkString("\n")
    s"$border\n$rows\n$border"
  }
}
