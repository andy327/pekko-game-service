package com.andy327.model.core

/**
 * Trait for Game objects that can be rendered as a human-readable string.
 *
 * This is useful for games that need a visual or textual representation, such as printing the board state of a game.
 */
trait Renderable {

  /** Returns a string representation of the object (e.g., game board or state). */
  def render: String

  override def toString: String = render
}
