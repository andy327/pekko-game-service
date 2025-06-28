package com.andy327.server.game

import com.andy327.model.core.PlayerId

/**
 * Represents a high-level, game-agnostic operation that can be executed against any game type.
 *
 * This abstraction allows the GameManager to handle different types of game actions without being tightly coupled to
 * game-specific message formats.
 */
sealed trait GameOperation

object GameOperation {

  /**
   * Represents a move submitted by a player in a game.
   * The exact structure of `move` depends on the game type (e.g., row/col for TicTacToe).
   *
   * @param playerId ID of the player making the move
   * @param move A polymorphic wrapper for the move details, defined in MovePayload
   */
  final case class MakeMove(playerId: PlayerId, move: MovePayload) extends GameOperation

  /**
   * Requests the current state of the game.
   */
  final case object GetState extends GameOperation
}
