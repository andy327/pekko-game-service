package com.andy327.server.http.json

import java.util.UUID

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, MediaTypes}
import spray.json._

import com.andy327.server.lobby.Player

/**
 * Spray-Json protocol + Pekko marshalling helpers.
 */
object JsonProtocol extends DefaultJsonProtocol {
  implicit val moveFormat: RootJsonFormat[TicTacToeMove] = jsonFormat3(TicTacToeMove)

  implicit val statusFormat: RootJsonFormat[TicTacToeState] = jsonFormat4(TicTacToeState.apply)

  implicit val uuidFormat: RootJsonFormat[UUID] = new RootJsonFormat[UUID] {
    def write(uuid: UUID): JsValue = JsString(uuid.toString)
    def read(value: JsValue): UUID = value match {
      case JsString(str) =>
        try UUID.fromString(str)
        catch {
          case _: IllegalArgumentException =>
            deserializationError(s"Invalid UUID string: $str")
        }
      case other => deserializationError(s"Expected UUID string, got: $other")
    }
  }

  implicit val playerFormat: RootJsonFormat[Player] = jsonFormat2(Player.apply)

  implicit val createLobbyRequestFormat: RootJsonFormat[CreateLobbyRequest] = jsonFormat1(CreateLobbyRequest)

  implicit val joinLobbyRequestFormat: RootJsonFormat[JoinLobbyRequest] = jsonFormat1(JoinLobbyRequest)

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
