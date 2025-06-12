package com.andy327.server.http.json

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, MediaTypes}
import spray.json._

/**
 * Spray-Json protocol + Pekko marshalling helpers.
 */
object JsonProtocol extends DefaultJsonProtocol {
  implicit val moveFormat: RootJsonFormat[TicTacToeMove] = jsonFormat3(TicTacToeMove)
  implicit val statusFormat: RootJsonFormat[TicTacToeState] = jsonFormat4(TicTacToeState.apply)

  /** Polymorphic marshaller that knows how to serialise any GameState into an HttpResponse. */
  implicit val gameStateMarshaller: ToResponseMarshaller[GameState] =
    Marshaller.withFixedContentType(MediaTypes.`application/json`) { gameState =>
      gameState match {
        case s: TicTacToeState =>
          val json = statusFormat.write(s).compactPrint
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json))
      }
    }
}
