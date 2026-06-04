package com.andy327.model.core

/** Shared contract for player marks (tokens/pieces) used in turn-based games.
  *
  * Each mark has a single-character symbol used for serialization and display. Game-specific mark types (e.g.,
  * `tictactoe.Mark`, `connectfour.Mark`) extend this trait as sealed hierarchies in their own packages.
  */
trait Mark {
  def symbol: String
  override def toString: String = symbol
}
