package com.andy327.model.core

trait Renderable {
  def render: String
  override def toString: String = render
}
