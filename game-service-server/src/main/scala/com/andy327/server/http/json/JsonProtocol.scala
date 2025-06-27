package com.andy327.server.http.json

import java.util.UUID

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, MediaTypes}
import spray.json._

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.GameManager.{ErrorResponse, LobbyCreated, LobbyJoined}
import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.lobby.{GameLifecycleStatus, GameMetadata, Player}

/**
 * Spray-Json protocol + Pekko marshalling helpers.
 */
object JsonProtocol extends DefaultJsonProtocol {
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

  implicit val playerRequestFormat: RootJsonFormat[PlayerRequest] = jsonFormat2(PlayerRequest.apply)

  implicit val playerFormat: RootJsonFormat[Player] = jsonFormat2(Player.apply)

  implicit val gameTypeFormat: RootJsonFormat[GameType] = new RootJsonFormat[GameType] {
    def write(gt: GameType): JsValue = JsString(gt.toString)

    def read(json: JsValue): GameType = json match {
      case JsString("TicTacToe") => GameType.TicTacToe
      case JsString(other)       => deserializationError(s"Unknown GameType: $other")
      case _                     => deserializationError("Expected GameType string")
    }
  }

  implicit val gameLifecycleStatusFormat: RootJsonFormat[GameLifecycleStatus] =
    new RootJsonFormat[GameLifecycleStatus] {
      def write(status: GameLifecycleStatus): JsValue = JsString(status.toString)
      def read(json: JsValue): GameLifecycleStatus = json match {
        case JsString("WaitingForPlayers") => GameLifecycleStatus.WaitingForPlayers
        case JsString("ReadyToStart")      => GameLifecycleStatus.ReadyToStart
        case JsString("InProgress")        => GameLifecycleStatus.InProgress
        case JsString("Completed")         => GameLifecycleStatus.Completed
        case JsString("Cancelled")         => GameLifecycleStatus.Cancelled
        case JsString(other)               => deserializationError(s"Unknown GameLifecycleStatus: $other")
        case _                             => deserializationError("Expected GameLifecycleStatus as string")
      }
    }

  implicit val gameMetadataFormat: RootJsonFormat[GameMetadata] = jsonFormat5(GameMetadata.apply)

  implicit val lobbyCreatedFormat: RootJsonFormat[LobbyCreated] = jsonFormat2(LobbyCreated.apply)

  implicit val lobbyJoinedFormat: RootJsonFormat[LobbyJoined] = jsonFormat3(LobbyJoined.apply)

  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)

  implicit val ticTacToeMoveFormat: RootJsonFormat[TicTacToeMove] = jsonFormat2(TicTacToeMove.apply)

  implicit val ticTacToeStateFormat: RootJsonFormat[TicTacToeState] = jsonFormat4(TicTacToeState.apply)

  /** Polymorphic marshaller that knows how to serialise any GameState into an HttpResponse. */
  implicit val gameStateMarshaller: ToResponseMarshaller[GameState] =
    Marshaller.withFixedContentType(MediaTypes.`application/json`) { gameState =>
      gameState match {
        case s: TicTacToeState =>
          val json = ticTacToeStateFormat.write(s).compactPrint
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json))
      }
    }
}
