package com.andy327.server.game

/**
 * A sealed trait representing a generic move payload for any game type.
 *
 * Specific games define their own move payloads as case classes extending this trait. This enables game-agnostic
 * handling of operations like "MakeMove" in shared logic such as GameManager.
 */
sealed trait MovePayload

object MovePayload {

  /**
   * A move for the Tic-Tac-Toe game, specifying a board position.
   *
   * @param row The row index (0-based)
   * @param col The column index (0-based)
   */
  final case class TicTacToeMove(row: Int, col: Int) extends MovePayload
}
