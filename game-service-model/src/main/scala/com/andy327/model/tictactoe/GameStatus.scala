package com.andy327.model.tictactoe

/**
 * Represents the current status of a TicTacToe game.
 */
sealed trait GameStatus
case object InProgress extends GameStatus
case class Won(winner: Mark) extends GameStatus
case object Draw extends GameStatus
