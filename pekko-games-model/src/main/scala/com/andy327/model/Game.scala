package com.andy327.model

trait Game[Move, State, Player, Status] {
  def currentState: State
  def currentPlayer: Player
  def gameStatus: Status

  /** Applies a move and returns the updated game or an error message */
  def play(move: Move): Either[String, Game[Move, State, Player, Status]]
}

trait Renderable {
  def render: String
  override def toString: String = render
}
