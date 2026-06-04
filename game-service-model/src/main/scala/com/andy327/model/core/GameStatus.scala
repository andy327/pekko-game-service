package com.andy327.model.core

/** The outcome state of a turn-based game.
  *
  * Parameterized over the mark type `M` so that [[Won]] carries the concrete winner type of the game (e.g.,
  * `tictactoe.Mark` or `connectfour.Mark`). [[InProgress]] and [[Draw]] carry no mark, so they extend
  * `GameStatus[Nothing]` and are valid wherever `GameStatus[M]` is expected (covariance).
  *
  * Transitions are one-way: a game starts as [[InProgress]] and moves to either [[Won]] or [[Draw]] once it reaches a
  * terminal position.
  */
sealed trait GameStatus[+M]

/** The game is still being played — no winner yet and at least one move remains. */
case object InProgress extends GameStatus[Nothing]

/** A player has won the game.
  *
  * @param winner the mark of the winning player
  */
case class Won[M](winner: M) extends GameStatus[M]

/** The game ended without a winner — the board is full or no further progress is possible. */
case object Draw extends GameStatus[Nothing]
