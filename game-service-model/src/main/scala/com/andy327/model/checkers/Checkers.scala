package com.andy327.model.checkers

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{Game, GameError, GameStatus, InProgress, PlayerId, Renderable, Won}

object Checkers {
  val Size: Int = 8

  /** The standard opening position, shared by every new game: twelve pawns per side on the dark squares of the three
    * rows nearest each player. Computed once since the immutable board is identical for all games.
    */
  private val StartingBoard: Board = Vector.tabulate(Size, Size) { (row, col) =>
    if (!isDark(row, col)) None
    else if (row <= 2) Some(Piece(Black, isKing = false))
    else if (row >= 5) Some(Piece(Red, isKing = false))
    else None
  }

  /** Creates a new game with the standard starting position. Red occupies rows 5–7 and moves first. */
  def empty(playerRed: PlayerId, playerBlack: PlayerId): Checkers =
    Checkers(playerRed, playerBlack, StartingBoard, currentPlayer = Red, winner = None, moveCount = 0)

  /** A square is dark — and therefore playable — when the sum of its coordinates is odd. */
  private def isDark(row: Int, col: Int): Boolean = (row + col) % 2 == 1

  private def onBoard(sq: Square): Boolean = sq.row >= 0 && sq.row < Size && sq.col >= 0 && sq.col < Size

  private def pieceAt(board: Board, sq: Square): Option[Piece] = board(sq.row)(sq.col)

  private def withPiece(board: Board, sq: Square, piece: Option[Piece]): Board =
    board.updated(sq.row, board(sq.row).updated(sq.col, piece))

  /** The row a pawn of `color` must reach to be crowned: Red advances up to row 0, Black down to the last row. */
  private def kingRow(color: Color): Int = color match {
    case Red   => 0
    case Black => Size - 1
  }

  private val RedForward: List[(Int, Int)] = List((-1, -1), (-1, 1))
  private val BlackForward: List[(Int, Int)] = List((1, -1), (1, 1))
  private val AllDiagonals: List[(Int, Int)] = RedForward ++ BlackForward

  /** The diagonal directions a piece may move or capture in: forward only for a pawn, all four for a king. */
  private def directions(piece: Piece): List[(Int, Int)] =
    if (piece.isKing) AllDiagonals
    else
      piece.color match {
        case Red   => RedForward
        case Black => BlackForward
      }

  private def opponentOf(color: Color): Color = color match {
    case Red   => Black
    case Black => Red
  }

  /** Non-capturing slides available to the piece on `from`: one diagonal step to an adjacent empty square. */
  private def simpleMoves(board: Board, from: Square, piece: Piece): List[Move] =
    directions(piece).flatMap { case (dr, dc) =>
      val target = Square(from.row + dr, from.col + dc)
      if (onBoard(target) && pieceAt(board, target).isEmpty) List(Move(from, List(target)))
      else Nil
    }

  /** Every complete jump chain the piece on `from` can make, as ordered lists of landing squares (excluding `from`).
    *
    * A jump hops an adjacent enemy piece to land on the empty square beyond it. After landing, the same piece must
    * continue jumping if it can, so this recurses on a board with the captured piece removed. A pawn that reaches its
    * king row by a jump is crowned and stops immediately — it does not continue the chain as a king.
    */
  private def jumpPaths(board: Board, from: Square, piece: Piece): List[List[Square]] =
    directions(piece).flatMap { case (dr, dc) =>
      val over = Square(from.row + dr, from.col + dc)
      val land = Square(from.row + 2 * dr, from.col + 2 * dc)
      val jumpable = onBoard(land) && pieceAt(board, land).isEmpty &&
        pieceAt(board, over).exists(_.color != piece.color)
      if (!jumpable) Nil
      else {
        val crowned = !piece.isKing && land.row == kingRow(piece.color)
        if (crowned) List(List(land))
        else {
          val afterHop = withPiece(withPiece(withPiece(board, from, None), over, None), land, Some(piece))
          val continuations = jumpPaths(afterHop, land, piece)
          if (continuations.isEmpty) List(List(land))
          else continuations.map(land :: _)
        }
      }
    }

  /** All legal moves for `color` in the given position.
    *
    * Captures are mandatory: if any jump exists, only jumps are legal; otherwise the simple slides are returned.
    */
  private def legalMovesFor(board: Board, color: Color): List[Move] = {
    val owned = for {
      row <- 0 until Size
      col <- 0 until Size
      sq = Square(row, col)
      piece <- pieceAt(board, sq) if piece.color == color
    } yield (sq, piece)

    val jumps = owned.toList.flatMap { case (sq, piece) => jumpPaths(board, sq, piece).map(Move(sq, _)) }
    if (jumps.nonEmpty) jumps
    else owned.toList.flatMap { case (sq, piece) => simpleMoves(board, sq, piece) }
  }

  /** Replays a validated move, returning the resulting board with captured pieces removed and crowning applied. */
  private def applyMove(board: Board, move: Move): Board = {
    var current = board
    var piece = pieceAt(board, move.from).get
    var pos = move.from
    move.steps.foreach { next =>
      current = withPiece(current, pos, None)
      if (math.abs(next.row - pos.row) == 2) {
        val over = Square((pos.row + next.row) / 2, (pos.col + next.col) / 2)
        current = withPiece(current, over, None)
      }
      if (!piece.isKing && next.row == kingRow(piece.color)) piece = piece.copy(isKing = true)
      current = withPiece(current, next, Some(piece))
      pos = next
    }
    current
  }

  /** True if `move`'s first hop spans two rows, i.e. it is a capture rather than a simple slide. */
  private def isCapture(move: Move): Boolean =
    move.steps.headOption.exists(step => math.abs(step.row - move.from.row) == 2)
}

/** Represents an instance of a Checkers game (English draughts), holding the current position and the rules for how it
  * evolves.
  *
  * Checkers is played on the dark squares of an 8×8 board, twelve pawns per side. Pawns slide one square diagonally
  * forward to an empty square, or jump an adjacent enemy piece to the empty square beyond it; captures are mandatory
  * and a multi-jump must be played to completion. A pawn reaching the far side is crowned a king and may thereafter
  * move and capture in all four diagonal directions. A player who has lost all their pieces, or who has no legal move
  * on their turn, loses.
  *
  * @param playerRed the player ID assigned to Red (moves first, home rows 5–7)
  * @param playerBlack the player ID assigned to Black (home rows 0–2)
  * @param board the 8×8 board; `board(row)(col)` holds the piece at that cell or None if empty
  * @param currentPlayer the color whose turn it is
  * @param winner the winning color if the game is over, otherwise None
  * @param moveCount the number of moves applied so far
  */
final case class Checkers(
    playerRed: PlayerId,
    playerBlack: PlayerId,
    board: Board,
    currentPlayer: Color,
    winner: Option[Color],
    moveCount: Int
) extends Game[Move, Checkers, Color, GameStatus[Color], GameError]
    with Renderable {

  import Checkers._

  def currentState: Checkers = this

  def gameStatus: GameStatus[Color] = winner.map(Won(_)).getOrElse(InProgress)

  /** Resolves `playerId` to `Red` or `Black` based on which seat they occupy; `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Color] =
    if (playerId == playerRed) Some(Red)
    else if (playerId == playerBlack) Some(Black)
    else None

  /** The roster in seat order: `Red` then `Black`. */
  def players: List[PlayerId] = List(playerRed, playerBlack)

  /** Applies `move` for `player`.
    *
    * Validates turn order and piece ownership, then checks the move against the legal moves for the position — which
    * enforces mandatory captures and complete multi-jumps. On success the move is played, the turn passes, and the game
    * is won if the opponent is left with no legal move.
    */
  def play(player: Color, move: Move): Either[GameError, Checkers] =
    if (gameStatus != InProgress)
      Left(GameOver)
    else if (player != currentPlayer)
      Left(InvalidTurn)
    else if (!onBoard(move.from))
      Left(IllegalMove)
    else
      pieceAt(board, move.from) match {
        case None                                 => Left(NoPieceThere)
        case Some(piece) if piece.color != player => Left(NotYourPiece)
        case Some(_)                              =>
          val legal = legalMovesFor(board, player)
          if (legal.contains(move)) {
            val newBoard = applyMove(board, move)
            val opponent = opponentOf(player)
            val opponentStuck = legalMovesFor(newBoard, opponent).isEmpty
            Right(
              copy(
                board = newBoard,
                currentPlayer = opponent,
                winner = if (opponentStuck) Some(player) else None,
                moveCount = moveCount + 1
              )
            )
          } else if (legal.exists(isCapture) && !isCapture(move))
            Left(CaptureRequired)
          else
            Left(IllegalMove)
      }

  /** Forfeits the game on behalf of the leaving player: the opponent is declared the winner.
    *
    * Rejects with [[core.GameError.InvalidPlayer]] if `playerId` is not a participant, or [[core.GameError.GameOver]]
    * if the game has already ended.
    */
  override def playerLeft(playerId: PlayerId): Either[GameError, Checkers] =
    playerFor(playerId) match {
      case None                                => Left(GameError.InvalidPlayer(playerId))
      case Some(_) if gameStatus != InProgress => Left(GameOver)
      case Some(leaver)                        => Right(copy(winner = Some(opponentOf(leaver))))
    }

  /** Returns a human-readable representation of the board for logging.
    *
    * Pawns render as `r`/`b`, kings as `R`/`B`, empty dark squares as `.` and light squares as a space.
    */
  def render: String = {
    val border = "-" * (Size * 2 + 1)
    val rows = board.zipWithIndex
      .map { case (cells, row) =>
        cells.zipWithIndex
          .map {
            case (Some(Piece(color, isKing)), _) =>
              val ch = color.symbol.head
              if (isKing) ch.toUpper.toString else ch.toLower.toString
            case (None, col) => if (isDark(row, col)) "." else " "
          }
          .mkString("|", "|", "|")
      }
      .mkString("\n")
    s"$border\n$rows\n$border"
  }
}
