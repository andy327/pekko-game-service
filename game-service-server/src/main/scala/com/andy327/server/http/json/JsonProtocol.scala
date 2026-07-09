package com.andy327.server.http.json

import io.circe.generic.semiauto.{deriveCodec, deriveEncoder}
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json}
import org.apache.pekko.http.scaladsl.unmarshalling.FromEntityUnmarshaller

import com.andy327.actor.chat.ChatCodecs
import com.andy327.actor.core.GameManager.{
  ActiveGameSummary,
  ChatHistory,
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
import com.andy327.actor.core.LobbyManager.LobbySummary
import com.andy327.actor.core.PlayerEvent
import com.andy327.actor.game.{
  BattleshipState,
  BidView,
  GameState,
  GridGameState,
  GuessResult,
  HoldEmHandResult,
  HoldEmPotAward,
  HoldEmSeat,
  HoldEmState,
  LiarsDiceState,
  MastermindState,
  PigState,
  RevealView
}
import com.andy327.actor.lobby.{GameLifecycleStatus, LobbyCodecs, LobbyMetadata, Player}
import com.andy327.actor.tracing.TraceEvent
import com.andy327.persistence.db.MoveRecord
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult
import com.andy327.persistence.db.schema.GameTypeCodecs.gameTypeCodec
import com.andy327.server.http.auth.{
  ChangePasswordRequest,
  ForgotPasswordRequest,
  LoginRequest,
  RegisterRequest,
  ResendVerificationRequest,
  ResetPasswordRequest,
  TokenResponse,
  VerifyEmailRequest,
  WhoamiResponse
}
import com.andy327.server.http.model.{ErrorResponse, MessageResponse}
import com.andy327.server.http.player.{PlayerGameSummary, PlayerHistory}

/** Circe codecs and Pekko HTTP marshallers for all API types.
  *
  * Case-class codecs are derived via `deriveCodec`. The value codecs for `Player`, `GameLifecycleStatus`, and
  * `LobbyMetadata` (which also fixes the `GameType` and UUID-key formats) are reused from `LobbyCodecs` and re-exported
  * as members here, so the wire format is defined once and is shared by the HTTP layer and Redis persistence.
  * [[playerEventEncoder]] is write-only (server-push only). Marshalling is provided by [[CirceSupport]]: any type with
  * an `Encoder` can be `complete`d, any type with a `Decoder` read with `entity(as[A])`.
  */
object JsonProtocol extends CirceSupport {

  // Canonical value codecs, re-exported as members so `import JsonProtocol._` brings them into implicit scope.
  implicit val playerCodec: Codec[Player] = LobbyCodecs.playerCodec
  implicit val gameLifecycleStatusCodec: Codec[GameLifecycleStatus] = LobbyCodecs.statusCodec
  implicit val lobbyMetadataCodec: Codec[LobbyMetadata] = LobbyCodecs.lobbyMetadataCodec

  // Auth request bodies.
  implicit val registerRequestCodec: Codec[RegisterRequest] = deriveCodec[RegisterRequest]
  implicit val loginRequestCodec: Codec[LoginRequest] = deriveCodec[LoginRequest]
  implicit val changePasswordRequestCodec: Codec[ChangePasswordRequest] = deriveCodec[ChangePasswordRequest]
  implicit val forgotPasswordRequestCodec: Codec[ForgotPasswordRequest] = deriveCodec[ForgotPasswordRequest]
  implicit val resetPasswordRequestCodec: Codec[ResetPasswordRequest] = deriveCodec[ResetPasswordRequest]
  implicit val verifyEmailRequestCodec: Codec[VerifyEmailRequest] = deriveCodec[VerifyEmailRequest]
  implicit val resendVerificationRequestCodec: Codec[ResendVerificationRequest] =
    deriveCodec[ResendVerificationRequest]

  // Auth response bodies.
  implicit val tokenResponseCodec: Codec[TokenResponse] = deriveCodec[TokenResponse]
  implicit val whoamiResponseCodec: Codec[WhoamiResponse] = deriveCodec[WhoamiResponse]

  // Shared HTTP envelopes: ErrorResponse is the single body for every non-2xx response; MessageResponse carries an
  // advisory note on a 2xx with nothing else to return. Both are neutral wire types, translated from actor replies at
  // the route boundary so this protocol never marshals an actor message directly.
  implicit val errorResponseCodec: Codec[ErrorResponse] = deriveCodec[ErrorResponse]
  implicit val messageResponseCodec: Codec[MessageResponse] = deriveCodec[MessageResponse]

  // Lobby and game responses completed directly by the routes.
  implicit val lobbyCreatedCodec: Codec[LobbyCreated] = deriveCodec[LobbyCreated]
  implicit val lobbyJoinedCodec: Codec[LobbyJoined] = deriveCodec[LobbyJoined]
  implicit val lobbyLeftCodec: Codec[LobbyLeft] = deriveCodec[LobbyLeft]
  implicit val gameStartedCodec: Codec[GameStarted] = deriveCodec[GameStarted]
  implicit val lobbySummaryCodec: Codec[LobbySummary] = deriveCodec[LobbySummary]
  implicit val lobbiesListedCodec: Codec[LobbiesListed] = deriveCodec[LobbiesListed]
  implicit val subscribeAcknowledgedCodec: Codec[SubscribeAcknowledged] = deriveCodec[SubscribeAcknowledged]
  implicit val unsubscribeAcknowledgedCodec: Codec[UnsubscribeAcknowledged] = deriveCodec[UnsubscribeAcknowledged]
  implicit val moveRecordCodec: Codec[MoveRecord] = deriveCodec[MoveRecord]
  implicit val moveHistoryCodec: Codec[MoveHistory] = deriveCodec[MoveHistory]

  // Write-only: pushed over the debug trace WebSocket (TraceRoutes), never read back from a request body.
  implicit val traceEventEncoder: Encoder[TraceEvent] = deriveEncoder[TraceEvent]

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
  implicit val pigStateCodec: Codec[PigState] = deriveCodec[PigState]
  implicit val guessResultCodec: Codec[GuessResult] = deriveCodec[GuessResult]
  implicit val mastermindStateCodec: Codec[MastermindState] = deriveCodec[MastermindState]
  implicit val bidViewCodec: Codec[BidView] = deriveCodec[BidView]
  implicit val revealViewCodec: Codec[RevealView] = deriveCodec[RevealView]
  implicit val liarsDiceStateCodec: Codec[LiarsDiceState] = deriveCodec[LiarsDiceState]
  implicit val holdEmSeatCodec: Codec[HoldEmSeat] = deriveCodec[HoldEmSeat]
  implicit val holdEmPotAwardCodec: Codec[HoldEmPotAward] = deriveCodec[HoldEmPotAward]
  implicit val holdEmHandResultCodec: Codec[HoldEmHandResult] = deriveCodec[HoldEmHandResult]
  implicit val holdEmStateCodec: Codec[HoldEmState] = deriveCodec[HoldEmState]

  /** Encoder for the polymorphic `GameState` hierarchy (grid games share `GridGameState`; Battleship has its own
    * per-viewer `BattleshipState`; Pig has `PigState`; Mastermind has `MastermindState`; Liar's Dice has
    * `LiarsDiceState`; Texas Hold 'Em has `HoldEmState`).
    */
  implicit val gameStateEncoder: Encoder[GameState] = Encoder.instance {
    case s: GridGameState   => s.asJson
    case s: BattleshipState => s.asJson
    case s: PigState        => s.asJson
    case s: MastermindState => s.asJson
    case s: LiarsDiceState  => s.asJson
    case s: HoldEmState     => s.asJson
  }

  // Entity unmarshallers, declared per-type so they don't shadow the predefined `String` unmarshaller (see
  // [[CirceSupport]]). Covers the request body read in routes and the response types read back in tests.
  implicit val registerRequestUnmarshaller: FromEntityUnmarshaller[RegisterRequest] =
    circeUnmarshaller[RegisterRequest]
  implicit val loginRequestUnmarshaller: FromEntityUnmarshaller[LoginRequest] = circeUnmarshaller[LoginRequest]
  implicit val changePasswordRequestUnmarshaller: FromEntityUnmarshaller[ChangePasswordRequest] =
    circeUnmarshaller[ChangePasswordRequest]
  implicit val forgotPasswordRequestUnmarshaller: FromEntityUnmarshaller[ForgotPasswordRequest] =
    circeUnmarshaller[ForgotPasswordRequest]
  implicit val resetPasswordRequestUnmarshaller: FromEntityUnmarshaller[ResetPasswordRequest] =
    circeUnmarshaller[ResetPasswordRequest]
  implicit val verifyEmailRequestUnmarshaller: FromEntityUnmarshaller[VerifyEmailRequest] =
    circeUnmarshaller[VerifyEmailRequest]
  implicit val resendVerificationRequestUnmarshaller: FromEntityUnmarshaller[ResendVerificationRequest] =
    circeUnmarshaller[ResendVerificationRequest]
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
    *   - `{"type":"LobbyUpdated",     "metadata":{...}, "spectatorCount":...}`
    *   - `{"type":"GameStateUpdated", "roomId":..., "state":{...}, "spectatorCount":...}`
    *   - `{"type":"GameEnded",        "result":"Completed"}`
    *   - `{"type":"ChatMessage",      "roomId":..., "senderId":..., "senderName":..., "text":..., "sentAt":...}`
    */
  implicit val playerEventEncoder: Encoder[PlayerEvent] = Encoder.instance {
    case PlayerEvent.LobbyUpdated(metadata, spectatorCount) =>
      Json.obj(
        "type" -> "LobbyUpdated".asJson,
        "metadata" -> metadata.asJson,
        "spectatorCount" -> spectatorCount.asJson
      )
    case PlayerEvent.GameStateUpdated(roomId, state, spectatorCount) =>
      Json.obj(
        "type" -> "GameStateUpdated".asJson,
        "roomId" -> roomId.asJson,
        "state" -> state.asJson,
        "spectatorCount" -> spectatorCount.asJson
      )
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
