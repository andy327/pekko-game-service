package com.andy327.server.http.json

import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import io.circe.{Codec, Encoder, Json}
import org.apache.pekko.http.scaladsl.unmarshalling.FromEntityUnmarshaller

import com.andy327.persistence.db.MoveRecord
import com.andy327.server.actors.core.GameManager.{
  ChatHistory,
  ErrorResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyJoined,
  LobbyLeft,
  MoveHistory,
  SubscribeAcknowledged
}
import com.andy327.server.actors.core.PlayerEvent
import com.andy327.server.chat.ChatCodecs
import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyCodecs, LobbyMetadata, Player}

/** Circe codecs and Pekko HTTP marshallers for all API types.
  *
  * Case-class codecs are derived via `deriveCodec`. The value codecs for `Player`, `GameLifecycleStatus`, and
  * `LobbyMetadata` (which also fixes the `GameType` and UUID-key formats) are reused from
  * [[com.andy327.server.lobby.LobbyCodecs]] and re-exported as members here, so the wire format is defined once and is
  * shared by the HTTP layer and Redis persistence. [[playerEventEncoder]] is write-only (server-push only). Marshalling
  * is provided by [[CirceSupport]]: any type with an `Encoder` can be `complete`d, any type with a `Decoder` read with
  * `entity(as[A])`.
  */
object JsonProtocol extends CirceSupport {

  // Canonical value codecs, re-exported as members so `import JsonProtocol._` brings them into implicit scope.
  implicit val playerCodec: Codec[Player] = LobbyCodecs.playerCodec
  implicit val gameLifecycleStatusCodec: Codec[GameLifecycleStatus] = LobbyCodecs.statusCodec
  implicit val lobbyMetadataCodec: Codec[LobbyMetadata] = LobbyCodecs.lobbyMetadataCodec

  implicit val playerRequestCodec: Codec[PlayerRequest] = deriveCodec[PlayerRequest]

  implicit val lobbyCreatedCodec: Codec[LobbyCreated] = deriveCodec[LobbyCreated]

  implicit val lobbyJoinedCodec: Codec[LobbyJoined] = deriveCodec[LobbyJoined]

  implicit val lobbyLeftCodec: Codec[LobbyLeft] = deriveCodec[LobbyLeft]

  implicit val gameStartedCodec: Codec[GameStarted] = deriveCodec[GameStarted]

  implicit val lobbiesListedCodec: Codec[LobbiesListed] = deriveCodec[LobbiesListed]

  implicit val errorResponseCodec: Codec[ErrorResponse] = deriveCodec[ErrorResponse]

  implicit val subscribeAcknowledgedCodec: Codec[SubscribeAcknowledged] = deriveCodec[SubscribeAcknowledged]

  implicit val moveRecordCodec: Codec[MoveRecord] = deriveCodec[MoveRecord]

  implicit val moveHistoryCodec: Codec[MoveHistory] = deriveCodec[MoveHistory]

  // Chat history records use the plain (untagged) chat-message form from ChatCodecs — see its scaladoc for how this
  // differs from the tagged ChatMessage envelope that playerEventEncoder emits for live WebSocket push.
  implicit val chatMessageCodec: Codec[PlayerEvent.ChatMessage] = ChatCodecs.chatMessageCodec

  implicit val chatHistoryCodec: Codec[ChatHistory] = deriveCodec[ChatHistory]

  // Game state views

  implicit val gridGameStateCodec: Codec[GridGameState] = deriveCodec[GridGameState]

  /** Encoder for the polymorphic `GameState` hierarchy; currently every view is a [[GridGameState]]. */
  implicit val gameStateEncoder: Encoder[GameState] = Encoder.instance { case s: GridGameState =>
    s.asJson
  }

  // Entity unmarshallers, declared per-type so they don't shadow the predefined `String` unmarshaller (see
  // [[CirceSupport]]). Covers the request body read in routes and the response types read back in tests.
  implicit val playerRequestUnmarshaller: FromEntityUnmarshaller[PlayerRequest] = circeUnmarshaller[PlayerRequest]
  implicit val lobbyMetadataUnmarshaller: FromEntityUnmarshaller[LobbyMetadata] = circeUnmarshaller[LobbyMetadata]
  implicit val lobbyCreatedUnmarshaller: FromEntityUnmarshaller[LobbyCreated] = circeUnmarshaller[LobbyCreated]
  implicit val lobbyJoinedUnmarshaller: FromEntityUnmarshaller[LobbyJoined] = circeUnmarshaller[LobbyJoined]
  implicit val lobbyLeftUnmarshaller: FromEntityUnmarshaller[LobbyLeft] = circeUnmarshaller[LobbyLeft]
  implicit val gameStartedUnmarshaller: FromEntityUnmarshaller[GameStarted] = circeUnmarshaller[GameStarted]
  implicit val lobbiesListedUnmarshaller: FromEntityUnmarshaller[LobbiesListed] = circeUnmarshaller[LobbiesListed]
  implicit val subscribeAcknowledgedUnmarshaller: FromEntityUnmarshaller[SubscribeAcknowledged] =
    circeUnmarshaller[SubscribeAcknowledged]
  implicit val moveHistoryUnmarshaller: FromEntityUnmarshaller[MoveHistory] = circeUnmarshaller[MoveHistory]
  implicit val chatHistoryUnmarshaller: FromEntityUnmarshaller[ChatHistory] = circeUnmarshaller[ChatHistory]

  /** Write-only encoder for PlayerEvent — serialises server-push events to JSON for delivery over WebSocket.
    *
    * Each variant is encoded as a JSON object with a `type` discriminator field so the client can dispatch on the event
    * kind without additional out-of-band information:
    *   - `{"type":"LobbyUpdated",     "metadata":{...}}`
    *   - `{"type":"GameStateUpdated", "state":{...}}`
    *   - `{"type":"GameEnded",        "result":"Completed"}`
    *   - `{"type":"ChatMessage",      "gameId":..., "senderId":..., "senderName":..., "text":..., "sentAt":...}`
    */
  implicit val playerEventEncoder: Encoder[PlayerEvent] = Encoder.instance {
    case PlayerEvent.LobbyUpdated(metadata) =>
      Json.obj("type" -> "LobbyUpdated".asJson, "metadata" -> metadata.asJson)
    case PlayerEvent.GameStateUpdated(state) =>
      Json.obj("type" -> "GameStateUpdated".asJson, "state" -> state.asJson)
    case PlayerEvent.GameEnded(result) =>
      Json.obj("type" -> "GameEnded".asJson, "result" -> (result: GameLifecycleStatus).asJson)
    case PlayerEvent.ChatMessage(gameId, senderId, senderName, text, sentAt) =>
      Json.obj(
        "type" -> "ChatMessage".asJson,
        "gameId" -> gameId.asJson,
        "senderId" -> senderId.asJson,
        "senderName" -> senderName.asJson,
        "text" -> text.asJson,
        "sentAt" -> sentAt.asJson
      )
  }
}
