package com.andy327.model.connectfour

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{Draw, Game, GameError, GameStatus, InProgress, PlayerId, Renderable, Won}

object ConnectFour {
  val Rows: Int = 6
  val Cols: Int = 7
  val WinLength: Int = 4

  /** Creates a new empty ConnectFour game with the specified players.
    *
    * Red always moves first.
    */
  def empty(playerRed: PlayerId, playerYellow: PlayerId): ConnectFour = ConnectFour(
    playerRed = playerRed,
    playerYellow = playerYellow,
    board = Vector.fill(Rows, Cols)(Option.empty[Mark]),
    currentPlayer = Red,
    winner = None,
    isDraw = false
  )

  /** Returns the lowest empty row index in the given column, or None if the column is full.
    *
    * Row indices increase downward (row 0 is top, row 5 is bottom), so pieces fall to the highest-indexed empty row.
    */
  private def lowestEmptyRow(board: Board, col: Int): Option[Int] =
    (Rows - 1 to 0 by -1).find(row => board(row)(col).isEmpty)

  /** Counts consecutive same-mark pieces in one direction from (row, col), not including (row, col) itself. */
  private def countDir(board: Board, row: Int, col: Int, dr: Int, dc: Int, mark: Mark): Int = {
    var count = 0
    var r = row + dr
    var c = col + dc
    while (r >= 0 && r < Rows && c >= 0 && c < Cols && board(r)(c).contains(mark)) {
      count += 1
      r += dr
      c += dc
    }
    count
  }

  private val WinDirections: Seq[(Int, Int)] = Seq((0, 1), (1, 0), (1, 1), (1, -1))

  /** Returns true if placing `mark` at `(row, col)` on `board` creates a line of [[WinLength]] or more.
    *
    * Checks all four axis directions: horizontal, vertical, diagonal down-right, diagonal down-left.
    */
  private def isWinningMove(board: Board, row: Int, col: Int, mark: Mark): Boolean =
    WinDirections.exists { case (dr, dc) =>
      countDir(board, row, col, dr, dc, mark) +
        countDir(board, row, col, -dr, -dc, mark) + 1 >= WinLength
    }
}

/** Represents an instance of a ConnectFour game, including both the current state and the rules for how the game
  * evolves.
  *
  * ConnectFour is played on a 6-row × 7-column board. Players alternate dropping pieces into columns; each piece falls
  * to the lowest empty row. The first player to connect four pieces in a horizontal, vertical, or diagonal line wins.
  * If the board fills with no winner the game is a draw.
  *
  * @param playerRed the player ID assigned to Red (moves first)
  * @param playerYellow the player ID assigned to Yellow
  * @param board the 6×7 board; `board(row)(col)` holds the mark at that cell or None if empty
  * @param currentPlayer the mark (Red or Yellow) whose turn it is
  * @param winner the winning mark if the game is over, otherwise None
  * @param isDraw true if the board is full with no winner
  */
final case class ConnectFour(
    playerRed: PlayerId,
    playerYellow: PlayerId,
    board: Board,
    currentPlayer: Mark,
    winner: Option[Mark],
    isDraw: Boolean
) extends Game[Drop, ConnectFour, Mark, GameStatus[Mark], GameError]
    with Renderable {

  import ConnectFour._

  def currentState: ConnectFour = this

  def gameStatus: GameStatus[Mark] =
    winner.map(Won(_)).getOrElse(if (isDraw) Draw else InProgress)

  /** Resolves `playerId` to `Red` or `Yellow` based on which seat they occupy; `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Mark] =
    if (playerId == playerRed) Some(Red)
    else if (playerId == playerYellow) Some(Yellow)
    else None

  /** The number of pieces dropped so far — one per move. */
  def moveCount: Int = board.flatten.count(_.isDefined)

  private def nextPlayer: Mark = currentPlayer match {
    case Red    => Yellow
    case Yellow => Red
  }

  /** Drops a piece for `player` into the column specified by `drop`.
    *
    * Validates turn order, column bounds, and column capacity. On success returns the updated game state with the piece
    * placed at the lowest empty row. Detects win (four in a line through the placed cell) and draw (board full with no
    * winner) after each move.
    */
  def play(player: Mark, drop: Drop): Either[GameError, ConnectFour] =
    if (gameStatus != InProgress)
      Left(GameOver)
    else if (player != currentPlayer)
      Left(InvalidTurn)
    else if (drop.col < 0 || drop.col >= Cols)
      Left(InvalidColumn)
    else
      lowestEmptyRow(board, drop.col) match {
        case None      => Left(ColumnFull)
        case Some(row) =>
          val newBoard = board.updated(row, board(row).updated(drop.col, Some(player)))
          val won = isWinningMove(newBoard, row, drop.col, player)
          val draw = !won && newBoard.forall(_.forall(_.isDefined))
          Right(
            copy(
              board = newBoard,
              currentPlayer = nextPlayer,
              winner = if (won) Some(player) else None,
              isDraw = draw
            )
          )
      }

  /** Returns a human-readable representation of the board for logging. */
  def render: String = {
    val border = "-" * (Cols * 2 + 1)
    val rows = board
      .map(_.map(_.fold(".")(_.toString)).mkString("|", "|", "|"))
      .mkString("\n")
    s"$border\n$rows\n$border"
  }
}
