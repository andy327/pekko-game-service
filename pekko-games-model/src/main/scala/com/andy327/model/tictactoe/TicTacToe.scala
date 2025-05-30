package com.andy327.model.tictactoe

import com.andy327.model.{Game, Renderable}

object TicTacToe {
  def empty: TicTacToe = TicTacToe(
    Vector.fill(3, 3)(Option.empty[Mark]),
    currentPlayer = X,
    status = InProgress
  )

  private def isFull(board: Board): Boolean = board.flatten.forall(_.isDefined)

  private def checkWinner(board: Board): Option[Mark] = {
    val rows = board
    val cols = (0 until 2).map(i => board.map(_(i)))
    val diag1 = (0 until 2).map(i => board(i)(i))
    val diag2 = (0 until 2).map(i => board(i)(2 - i))

    (rows ++ cols :+ diag1 :+ diag2)
      .find(line => line.forall(_.isDefined) && line.distinct.size == 1)
      .flatMap(_.head)
  }
}

case class TicTacToe(
    board: Board,
    currentPlayer: Mark,
    status: GameStatus
) extends Game[Location, TicTacToe, Mark, GameStatus]
    with Renderable {

  def currentState: TicTacToe = this
  def gameStatus: GameStatus = status

  private def nextPlayer: Mark = currentPlayer match {
    case X => O
    case O => X
  }

  def play(move: Location): Either[String, Game[Location, TicTacToe, Mark, GameStatus]] = {
    if (status != InProgress) Left("Game is already finished.")
    else if (board(move.row)(move.col).isDefined) Left("Cell already occupied.")
    else {
      val updatedBoard = board.updated(move.row,
        board(move.row).updated(move.col, Some(currentPlayer)))

      val newStatus = TicTacToe.checkWinner(updatedBoard).map(Won).getOrElse {
        if (TicTacToe.isFull(updatedBoard)) Draw
        else InProgress
      }

      Right(copy(board = updatedBoard, currentPlayer = nextPlayer, status = newStatus))
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
