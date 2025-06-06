package com.andy327.server.http.json

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, MediaTypes}
import spray.json._

case class TicTacToeMove(playerId: String, row: Int, col: Int)

/** Super-type for every serialisable “view” of game state returned to the client. */
sealed trait GameState
case class TicTacToeState(board: Vector[Vector[String]], currentPlayer: String, winner: Option[String], draw: Boolean)
    extends GameState

/**
 * Spray-Json protocol + Pekko marshalling helpers.
 */
object JsonProtocol extends DefaultJsonProtocol {
  implicit val moveFormat: RootJsonFormat[TicTacToeMove] = jsonFormat3(TicTacToeMove)
  implicit val statusFormat: RootJsonFormat[TicTacToeState] = jsonFormat4(TicTacToeState)

  /** Polymorphic marshaller that knows how to serialise any GameState into an HttpResponse. */
  implicit val gameStateMarshaller: ToResponseMarshaller[GameState] =
    Marshaller.withFixedContentType(MediaTypes.`application/json`) { gameState =>
      gameState match {
        case s: TicTacToeState =>
          val json = statusFormat.write(s).compactPrint
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json))
        case other =>
          throw new RuntimeException(s"Cannot marshal unknown GameState: $other")
      }
    }
}
