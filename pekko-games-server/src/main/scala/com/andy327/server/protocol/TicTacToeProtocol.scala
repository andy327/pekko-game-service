package com.andy327.server.protocol

import spray.json._

case class Move(playerId: String, row: Int, col: Int)
case class TicTacToeStatus(board: Vector[Vector[String]], currentPlayer: String, winner: Option[String], draw: Boolean)

object JsonProtocol extends DefaultJsonProtocol {
  implicit val moveFormat: RootJsonFormat[Move] = jsonFormat3(Move)
  implicit val statusFormat: RootJsonFormat[TicTacToeStatus] = jsonFormat4(TicTacToeStatus)
}
