package com.andy327.actor.bot

import scala.util.Random

import com.andy327.actor.game.{GameView, GridGameView, MovePayload}
import com.andy327.model.core.Mark
import com.andy327.model.tictactoe.{O, X}

/** An unbeatable Tic-Tac-Toe policy: full-width minimax over the 3×3 board.
  *
  * Every offered move is scored by the value of the position it leads to under optimal play by both sides, and one of
  * the top-scoring moves is played. The search is exhaustive — the game tree is tiny — so the bot always takes an
  * immediate win, always blocks an immediate loss, and never loses. Scores are shaded by depth (a win is worth more
  * the sooner it lands, a loss less the later it is forced), so the bot presses a winning line and drags out a lost
  * one rather than treating every eventual outcome as equal.
  *
  * Ties are broken with `rng`, so among the moves that are equally optimal — every opening move, since perfect play is
  * a draw — the choice varies from game to game while never dropping below optimal. A seeded instance replays
  * identically, like every [[AiPolicy]].
  *
  * The policy reads only a [[com.andy327.actor.game.GridGameView]]: cells carry the domain `Mark`, and the two tokens
  * are the game's own `X` and `O`. A view of some other grid game (a non-3×3 board) falls through to [[RandomPolicy]],
  * which always yields a legal move; in practice [[BotPolicies]] only ever hands this policy a Tic-Tac-Toe view.
  */
object TicTacToePolicy extends AiPolicy {

  private type Board = Vector[Vector[Option[Mark]]]

  /** Side length of the board this policy searches. */
  private val BoardSize = 3

  /** Terminal value of a win, before the depth penalty; a loss is its negation. */
  private val WinScore = 10

  override def decide(view: GameView, rng: Random): Option[MovePayload] = view match {
    case grid: GridGameView if grid.board.length == BoardSize && grid.board.forall(_.length == BoardSize) =>
      bestMove(grid, rng)
    case _ => RandomPolicy.decide(view, rng)
  }

  /** Picks uniformly at random among the offered moves whose resulting position scores highest for the mover. */
  private def bestMove(view: GridGameView, rng: Random): Option[MovePayload] = {
    val me = view.currentPlayer
    val scored = view.legalMoves.collect { case move @ MovePayload.TicTacToeMove(row, col) =>
      move -> minimax(place(view.board, row, col, me), toMove = other(me), me = me, depth = 1)
    }
    if (scored.isEmpty) None
    else {
      val best = scored.map(_._2).max
      val ties = scored.collect { case (move, score) if score == best => move }
      Some(ties(rng.nextInt(ties.size)))
    }
  }

  /** Value of `board` for `me` with `toMove` to play: `WinScore` shaded by `depth` for a decided line, 0 for a draw. */
  private def minimax(board: Board, toMove: Mark, me: Mark, depth: Int): Int =
    winnerOf(board) match {
      case Some(mark) if mark == me => WinScore - depth
      case Some(_)                  => depth - WinScore
      case None                     =>
        val cells = emptyCells(board)
        if (cells.isEmpty) 0 // a full board with no winner is a draw
        else {
          val scores = cells.map { case (row, col) =>
            minimax(place(board, row, col, toMove), other(toMove), me, depth + 1)
          }
          if (toMove == me) scores.max else scores.min
        }
    }

  /** The other token: `X` and `O` are the only two in play. */
  private def other(mark: Mark): Mark = if (mark == X) O else X

  private def place(board: Board, row: Int, col: Int, mark: Mark): Board =
    board.updated(row, board(row).updated(col, Some(mark)))

  private def emptyCells(board: Board): List[(Int, Int)] =
    (for {
      row <- board.indices
      col <- board(row).indices
      if board(row)(col).isEmpty
    } yield (row, col)).toList

  /** The mark on a completed line (row, column, or diagonal), or `None` if no line is filled by one player. */
  private def winnerOf(board: Board): Option[Mark] = {
    val rows = board
    val cols = (0 until BoardSize).map(i => board.map(_(i)))
    val diag1 = (0 until BoardSize).map(i => board(i)(i))
    val diag2 = (0 until BoardSize).map(i => board(i)(BoardSize - 1 - i))
    (rows ++ cols :+ diag1 :+ diag2)
      .find(line => line.forall(_.isDefined) && line.distinct.size == 1)
      .flatMap(_.head)
  }
}
