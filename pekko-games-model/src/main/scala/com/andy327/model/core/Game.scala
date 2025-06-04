package com.andy327.model.core

trait Game[Move, State, Player, Status, Error] {
  def currentState: State
  def currentPlayer: Player
  def gameStatus: Status

  /** Applies a move and returns the updated game or an error message */
  def play(player: Player, move: Move): Either[Error, State]
}
