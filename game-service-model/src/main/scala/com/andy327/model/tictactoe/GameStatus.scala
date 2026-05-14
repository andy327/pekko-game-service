package com.andy327.model.tictactoe

/** The outcome state of a TicTacToe game.
  *
  * Transitions are one-way: a game starts as [[InProgress]] and moves to either [[Won]] or [[Draw]] once the board
  * reaches a terminal position.
  */
sealed trait GameStatus

/** The game is still being played — no winner yet and empty cells remain. */
case object InProgress extends GameStatus

/** A player has completed a winning line. @param winner the mark (X or O) that won. */
case class Won(winner: Mark) extends GameStatus

/** All nine cells are filled and no player completed a line. */
case object Draw extends GameStatus
