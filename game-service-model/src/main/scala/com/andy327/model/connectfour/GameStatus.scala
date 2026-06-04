package com.andy327.model.connectfour

/** The outcome state of a ConnectFour game.
  *
  * Transitions are one-way: a game starts as [[InProgress]] and moves to either [[Won]] or [[Draw]] once the board
  * reaches a terminal position.
  */
sealed trait GameStatus

/** The game is still being played — no winner yet and at least one empty cell remains. */
case object InProgress extends GameStatus

/** A player has connected four pieces in a line.
  *
  * @param winner the mark (Red or Yellow) that won.
  */
case class Won(winner: Mark) extends GameStatus

/** The board is completely filled and no player connected four in a line. */
case object Draw extends GameStatus
