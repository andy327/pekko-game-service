package com.andy327.server.http.json

import io.circe.generic.semiauto.deriveCodec
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import org.apache.pekko.http.scaladsl.unmarshalling.FromEntityUnmarshaller

import com.andy327.actor.chat.ChatCodecs
import com.andy327.actor.core.GameManager.{
  ActiveGameSummary,
  ChatHistory,
  ErrorResponse,
  GameStarted,
  LobbiesListed,
  LobbyCreated,
  LobbyJoined,
  LobbyLeft,
  MoveHistory,
  PlayerSessions,
  SubscribeAcknowledged,
  UnsubscribeAcknowledged
}
import com.andy327.actor.core.PlayerEvent
import com.andy327.actor.game.{BattleshipState, GameState, GridGameState}
import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyCodecs, LobbyMetadata, Player}
import com.andy327.persistence.db.MoveRecord
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult
import com.andy327.persistence.db.schema.GameTypeCodecs.gameTypeCodec
import com.andy327.server.http.auth.{
  ChangePasswordRequest,
  LoginRequest,
  PlayerGameSummary,
  PlayerHistory,
  RegisterRequest
}

/** Circe codecs and Pekko HTTP marshallers for all API types.
  *
  * Case-class codecs are derived via `deriveCodec`. The value codecs for `Player`, `GameLifecycleStatus`, and
  * `LobbyMetadata` (which also fixes the `GameType` and UUID-key formats) are reused from
  * `LobbyCodecs` and re-exported as members here, so the wire format is defined once and is
  * shared by the HTTP layer and Redis persistence. [[playerEventEncoder]] is write-only (server-push only). Marshalling
  * is provided by [[CirceSupport]]: any type with an `Encoder` can be `complete`d, any type with a `Decoder` read with
  * `entity(as[A])`.
  */
object JsonProtocol extends CirceSupport {

  // Canonical value codecs, re-exported as members so `import JsonProtocol._` brings them into implicit scope.
  implicit val playerCodec: Codec[Player] = LobbyCodecs.playerCodec
  implicit val gameLifecycleStatusCodec: Codec[GameLifecycleStatus] = LobbyCodecs.statusCodec
  implicit val lobbyMetadataCodec: Codec[LobbyMetadata] = LobbyCodecs.lobbyMetadataCodec

  implicit val registerRequestCodec: Codec[RegisterRequest] = deriveCodec[RegisterRequest]

  implicit val loginRequestCodec: Codec[LoginRequest] = deriveCodec[LoginRequest]

  implicit val changePasswordRequestCodec: Codec[ChangePasswordRequest] = deriveCodec[ChangePasswordRequest]

  implicit val lobbyCreatedCodec: Codec[LobbyCreated] = deriveCodec[LobbyCreated]

  implicit val lobbyJoinedCodec: Codec[LobbyJoined] = deriveCodec[LobbyJoined]

  implicit val lobbyLeftCodec: Codec[LobbyLeft] = deriveCodec[LobbyLeft]

  implicit val gameStartedCodec: Codec[GameStarted] = deriveCodec[GameStarted]

  implicit val lobbiesListedCodec: Codec[LobbiesListed] = deriveCodec[LobbiesListed]

  implicit val errorResponseCodec: Codec[ErrorResponse] = deriveCodec[ErrorResponse]

  implicit val subscribeAcknowledgedCodec: Codec[SubscribeAcknowledged] = deriveCodec[SubscribeAcknowledged]

  implicit val unsubscribeAcknowledgedCodec: Codec[UnsubscribeAcknowledged] = deriveCodec[UnsubscribeAcknowledged]

  implicit val moveRecordCodec: Codec[MoveRecord] = deriveCodec[MoveRecord]

  implicit val moveHistoryCodec: Codec[MoveHistory] = deriveCodec[MoveHistory]

  // Chat history records use the plain (untagged) chat-message form from ChatCodecs — see its scaladoc for how this
  // differs from the tagged ChatMessage envelope that playerEventEncoder emits for live WebSocket push.
  implicit val chatMessageCodec: Codec[PlayerEvent.ChatMessage] = ChatCodecs.chatMessageCodec

  implicit val chatHistoryCodec: Codec[ChatHistory] = deriveCodec[ChatHistory]

  // Player history: a GameResult travels as its stable lower-case label ("win"/"loss"/"draw"); the GameType field
  // reuses the canonical wire format from GameTypeCodecs so it matches the rest of the API.
  implicit val gameResultCodec: Codec[GameResult] = Codec.from(
    Decoder.decodeString.emap(s => GameResult.fromLabel(s).toRight(s"Unknown GameResult: $s")),
    Encoder.encodeString.contramap[GameResult](_.label)
  )

  implicit val playerGameSummaryCodec: Codec[PlayerGameSummary] = deriveCodec[PlayerGameSummary]

  implicit val playerHistoryCodec: Codec[PlayerHistory] = deriveCodec[PlayerHistory]

  // Player sessions: the live "what am I in?" view, completed directly like LobbiesListed. ActiveGameSummary reuses the
  // canonical GameType wire format; the lobbies field reuses the shared LobbyMetadata codec, matching the /lobby shape.
  implicit val activeGameSummaryCodec: Codec[ActiveGameSummary] = deriveCodec[ActiveGameSummary]

  implicit val playerSessionsCodec: Codec[PlayerSessions] = deriveCodec[PlayerSessions]

  // Game state views

  implicit val gridGameStateCodec: Codec[GridGameState] = deriveCodec[GridGameState]

  implicit val battleshipStateCodec: Codec[BattleshipState] = deriveCodec[BattleshipState]

  /** Encoder for the polymorphic `GameState` hierarchy (grid games share `GridGameState`;
    * Battleship has its own per-viewer `BattleshipState`).
    */
  implicit val gameStateEncoder: Encoder[GameState] = Encoder.instance {
    case s: GridGameState   => s.asJson
    case s: BattleshipState => s.asJson
  }

  // Entity unmarshallers, declared per-type so they don't shadow the predefined `String` unmarshaller (see
  // [[CirceSupport]]). Covers the request body read in routes and the response types read back in tests.
  implicit val registerRequestUnmarshaller: FromEntityUnmarshaller[RegisterRequest] =
    circeUnmarshaller[RegisterRequest]
  implicit val loginRequestUnmarshaller: FromEntityUnmarshaller[LoginRequest] = circeUnmarshaller[LoginRequest]
  implicit val changePasswordRequestUnmarshaller: FromEntityUnmarshaller[ChangePasswordRequest] =
    circeUnmarshaller[ChangePasswordRequest]
  implicit val lobbyMetadataUnmarshaller: FromEntityUnmarshaller[LobbyMetadata] = circeUnmarshaller[LobbyMetadata]
  implicit val lobbyCreatedUnmarshaller: FromEntityUnmarshaller[LobbyCreated] = circeUnmarshaller[LobbyCreated]
  implicit val lobbyJoinedUnmarshaller: FromEntityUnmarshaller[LobbyJoined] = circeUnmarshaller[LobbyJoined]
  implicit val lobbyLeftUnmarshaller: FromEntityUnmarshaller[LobbyLeft] = circeUnmarshaller[LobbyLeft]
  implicit val gameStartedUnmarshaller: FromEntityUnmarshaller[GameStarted] = circeUnmarshaller[GameStarted]
  implicit val lobbiesListedUnmarshaller: FromEntityUnmarshaller[LobbiesListed] = circeUnmarshaller[LobbiesListed]
  implicit val subscribeAcknowledgedUnmarshaller: FromEntityUnmarshaller[SubscribeAcknowledged] =
    circeUnmarshaller[SubscribeAcknowledged]
  implicit val unsubscribeAcknowledgedUnmarshaller: FromEntityUnmarshaller[UnsubscribeAcknowledged] =
    circeUnmarshaller[UnsubscribeAcknowledged]
  implicit val moveHistoryUnmarshaller: FromEntityUnmarshaller[MoveHistory] = circeUnmarshaller[MoveHistory]
  implicit val chatHistoryUnmarshaller: FromEntityUnmarshaller[ChatHistory] = circeUnmarshaller[ChatHistory]
  implicit val playerHistoryUnmarshaller: FromEntityUnmarshaller[PlayerHistory] = circeUnmarshaller[PlayerHistory]
  implicit val playerSessionsUnmarshaller: FromEntityUnmarshaller[PlayerSessions] =
    circeUnmarshaller[PlayerSessions]

  /** Write-only encoder for PlayerEvent — serialises server-push events to JSON for delivery over WebSocket.
    *
    * Each variant is encoded as a JSON object with a `type` discriminator field so the client can dispatch on the event
    * kind without additional out-of-band information:
    *   - `{"type":"LobbyUpdated",     "metadata":{...}}`
    *   - `{"type":"GameStateUpdated", "roomId":..., "state":{...}}`
    *   - `{"type":"GameEnded",        "result":"Completed"}`
    *   - `{"type":"ChatMessage",      "roomId":..., "senderId":..., "senderName":..., "text":..., "sentAt":...}`
    */
  implicit val playerEventEncoder: Encoder[PlayerEvent] = Encoder.instance {
    case PlayerEvent.LobbyUpdated(metadata) =>
      Json.obj("type" -> "LobbyUpdated".asJson, "metadata" -> metadata.asJson)
    case PlayerEvent.GameStateUpdated(roomId, state) =>
      Json.obj("type" -> "GameStateUpdated".asJson, "roomId" -> roomId.asJson, "state" -> state.asJson)
    case PlayerEvent.GameEnded(result) =>
      Json.obj("type" -> "GameEnded".asJson, "result" -> (result: GameLifecycleStatus).asJson)
    case PlayerEvent.ChatMessage(roomId, senderId, senderName, text, sentAt) =>
      Json.obj(
        "type" -> "ChatMessage".asJson,
        "roomId" -> roomId.asJson,
        "senderId" -> senderId.asJson,
        "senderName" -> senderName.asJson,
        "text" -> text.asJson,
        "sentAt" -> sentAt.asJson
      )
  }
}
