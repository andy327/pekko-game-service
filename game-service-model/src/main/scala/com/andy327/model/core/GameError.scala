package com.andy327.model.core

trait GameError {
  def message: String
}
object GameError {
  case class Unknown(message: String) extends GameError
}
