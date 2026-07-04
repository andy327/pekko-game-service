package com.andy327.actor.game

/** A sealed trait representing a generic move payload for any game type.
  *
  * Specific games define their own move payloads as case classes extending this trait. This enables game-agnostic
  * handling of operations like "MakeMove" in shared logic such as GameManager.
  */
sealed trait MovePayload

object MovePayload {

  /** A move for the Tic-Tac-Toe game, specifying a board position.
    *
    * @param row The row index (0-based)
    * @param col The column index (0-based)
    */
  final case class TicTacToeMove(row: Int, col: Int) extends MovePayload

  /** A move for ConnectFour: drop a piece into the specified column.
    *
    * @param col The column index (0-based, 0–6)
    */
  final case class ConnectFourMove(col: Int) extends MovePayload

  /** A move for Battleship: fire a shot at a coordinate on the opponent's board.
    *
    * @param row The row index (0-based)
    * @param col The column index (0-based)
    */
  final case class BattleshipMove(row: Int, col: Int) extends MovePayload

  /** A move for Pig: either roll the die (`"roll"`) or bank the turn score (`"hold"`).
    *
    * @param action `"roll"` or `"hold"`
    */
  final case class PigAction(action: String) extends MovePayload

  /** A move for Mastermind: the codemaker sets the code (`"setcode"`) or the codebreaker guesses it (`"guess"`).
    *
    * @param action `"setcode"` or `"guess"`
    * @param pegs the color names making up the code or guess (e.g. `["red","blue","red","green"]`)
    */
  final case class MastermindAction(action: String, pegs: List[String]) extends MovePayload

  /** A move for Liar's Dice: raise the bid (`"bid"`) or call "Liar" (`"challenge"`).
    *
    * For a bid, `quantity` is required and `face` is the numbered face 2–6, or `None`/absent for a wild "ones" bid. A
    * challenge ignores both fields. The dice a challenge re-rolls are supplied server-side, never by the client.
    *
    * @param action `"bid"` or `"challenge"`
    * @param quantity the bid's quantity (dice claimed), for a `"bid"` action
    * @param face the bid's numbered face 2–6, or `None` for a wild "ones" bid
    */
  final case class LiarsDiceAction(action: String, quantity: Option[Int], face: Option[Int]) extends MovePayload

  /** A move for Texas Hold 'Em: `fold`, `check`, `call`, `bet`, or `raise`.
    *
    * `amount` is the total chip target for a `bet` or `raise` (the seat's street contribution after the action) and is
    * absent for `fold`, `check`, and `call`. The deck a hand-starting action shuffles is supplied server-side, never by
    * the client.
    *
    * @param action `fold`, `check`, `call`, `bet`, or `raise`
    * @param amount the total to bet or raise to, for a `bet` or `raise` action
    */
  final case class HoldEmAction(action: String, amount: Option[Int]) extends MovePayload
}
