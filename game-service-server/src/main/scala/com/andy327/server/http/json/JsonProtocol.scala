package com.andy327.server.http.json

import java.util.UUID

import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToResponseMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, HttpResponse, MediaTypes}
import spray.json._

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.GameManager.{
  ErrorResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyJoined,
  LobbyLeft,
  SubscribeAcknowledged
}
import com.andy327.server.actors.core.PlayerEvent
import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyMetadata, Player}

/** Spray-JSON read/write formats and Pekko HTTP marshallers for all API types.
  *
  * Case-class formats are derived via `jsonFormatN`; enums and sum types use hand-written instances so the wire format
  * stays stable even if the class name changes. The [[playerEventFormat]] is write-only (server-push only).
  * The [[gameStateMarshaller]] handles polymorphic `GameState` → `HttpResponse` conversion.
  */
object JsonProtocol extends DefaultJsonProtocol {

  /** Serialises UUIDs as plain strings; rejects non-UUID strings at read time. */
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

  /** Serialises GameType as its `toString` name; rejects unknown strings at read time. */
  implicit val gameTypeFormat: RootJsonFormat[GameType] = new RootJsonFormat[GameType] {
    def write(gt: GameType): JsValue = JsString(gt.toString)

    def read(json: JsValue): GameType = json match {
      case JsString("TicTacToe") => GameType.TicTacToe
      case JsString(other)       => deserializationError(s"Unknown GameType: $other")
      case _                     => deserializationError("Expected GameType string")
    }
  }

  /** Serialises GameLifecycleStatus as its `toString` name; rejects unknown strings at read time. */
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

  implicit val lobbyMetadataFormat: RootJsonFormat[LobbyMetadata] = jsonFormat5(LobbyMetadata.apply)

  implicit val lobbyCreatedFormat: RootJsonFormat[LobbyCreated] = jsonFormat2(LobbyCreated.apply)

  implicit val lobbyJoinedFormat: RootJsonFormat[LobbyJoined] = jsonFormat3(LobbyJoined.apply)

  implicit val lobbyLeftFormat: RootJsonFormat[LobbyLeft] = jsonFormat2(LobbyLeft.apply)

  implicit val gameStartFormat: RootJsonFormat[GameStarted] = jsonFormat1(GameStarted.apply)

  implicit val lobbiesListedFormat: RootJsonFormat[LobbiesListed] = jsonFormat4(LobbiesListed.apply)

  implicit val errorResponseFormat: RootJsonFormat[ErrorResponse] = jsonFormat1(ErrorResponse.apply)

  implicit val subscribeAcknowledgedFormat: RootJsonFormat[SubscribeAcknowledged] =
    jsonFormat1(SubscribeAcknowledged.apply)

  // Tic-tac-toe

  implicit val ticTacToeMoveRequestFormat: RootJsonFormat[TicTacToeMoveRequest] =
    jsonFormat2(TicTacToeMoveRequest.apply)

  implicit val ticTacToeStateFormat: RootJsonFormat[TicTacToeState] = jsonFormat4(TicTacToeState.apply)

  // ConnectFour

  implicit val connectFourMoveRequestFormat: RootJsonFormat[ConnectFourMoveRequest] =
    jsonFormat1(ConnectFourMoveRequest.apply)

  implicit val connectFourStateFormat: RootJsonFormat[ConnectFourState] = jsonFormat4(ConnectFourState.apply)

  /** Write-only format for PlayerEvent — serialises server-push events to JSON for delivery over WebSocket.
    *
    * Each variant is encoded as a JSON object with a `type` discriminator field so the client can dispatch on the event
    * kind without additional out-of-band information:
    *   - `{"type":"LobbyUpdated",    "metadata":{...}}`
    *   - `{"type":"GameStateUpdated","state":{...}}`
    *   - `{"type":"GameEnded",       "result":"Completed"}`
    *
    * Reading is not supported; deserializationError is raised if attempted.
    */
  implicit val playerEventFormat: RootJsonFormat[PlayerEvent] = new RootJsonFormat[PlayerEvent] {
    def write(event: PlayerEvent): JsValue = event match {
      case PlayerEvent.LobbyUpdated(metadata) =>
        JsObject("type" -> JsString("LobbyUpdated"), "metadata" -> lobbyMetadataFormat.write(metadata))
      case PlayerEvent.GameStateUpdated(state) =>
        val stateJson = state match {
          case s: TicTacToeState   => ticTacToeStateFormat.write(s)
          case s: ConnectFourState => connectFourStateFormat.write(s)
        }
        JsObject("type" -> JsString("GameStateUpdated"), "state" -> stateJson)
      case PlayerEvent.GameEnded(result) =>
        JsObject("type" -> JsString("GameEnded"), "result" -> gameLifecycleStatusFormat.write(result))
    }

    def read(json: JsValue): PlayerEvent =
      deserializationError("PlayerEvent deserialization is not supported")
  }

  /** Polymorphic marshaller that knows how to serialise any GameState into an HttpResponse. */
  implicit val gameStateMarshaller: ToResponseMarshaller[GameState] =
    Marshaller.withFixedContentType(MediaTypes.`application/json`) { gameState =>
      gameState match {
        case s: TicTacToeState =>
          val json = ticTacToeStateFormat.write(s).compactPrint
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json))
        case s: ConnectFourState =>
          val json = connectFourStateFormat.write(s).compactPrint
          HttpResponse(entity = HttpEntity(ContentTypes.`application/json`, json))
      }
    }
}
