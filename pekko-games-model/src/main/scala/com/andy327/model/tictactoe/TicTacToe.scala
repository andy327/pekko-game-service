package com.andy327.model.tictactoe

import com.andy327.model.{Game, Renderable}
import com.andy327.model.tictactoe._
import com.andy327.model.tictactoe.GameError._

object TicTacToe {
  def empty(playerX: String, playerO: String): TicTacToe = TicTacToe(
    playerX = playerX,
    playerO = playerO,
    board = Vector.fill(3, 3)(Option.empty[Mark]),
    currentPlayer = X,
    winner = None,
    isDraw = false
  )

  private def isFull(board: Board): Boolean =
    board.flatten.forall(_.isDefined)

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

final case class TicTacToe(
    playerX: String,
    playerO: String,
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

  def play(player: Mark, loc: Location): Either[GameError, TicTacToe] = {
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

      Right(copy(
        board = updatedBoard,
        currentPlayer = nextPlayer,
        winner = maybeWinner,
        isDraw = draw
      ))
    }
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
