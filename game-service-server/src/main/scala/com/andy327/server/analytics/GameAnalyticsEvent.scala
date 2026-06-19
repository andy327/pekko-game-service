package com.andy327.server.analytics

import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

import com.andy327.model.core.{GameId, GameType, PlayerId}
import com.andy327.persistence.db.schema.GameTypeCodecs.gameTypeCodec

/** Structured domain events describing the lifecycle of a game, emitted from the actor layer and consumed by the
  * analytics pipeline.
  *
  * These are deliberately distinct from [[com.andy327.server.actors.core.PlayerEvent]]: PlayerEvents are
  * transport-facing (they carry rendered board state for WebSocket delivery), whereas these carry only the lightweight
  * dimensions analytics needs (game type, outcome, counts). They are published on the dedicated `game-analytics`
  * channel and decoded by [[AnalyticsConsumer]], so the codec must round-trip.
  */
sealed trait GameAnalyticsEvent {

  /** The game this event refers to. */
  def gameId: GameId
}

object GameAnalyticsEvent {

  /** How a game ended. `Won`/`Draw` are reached through normal play; `Forfeit` when a player leaves an in-progress
    * game. Winner identity is intentionally omitted — there is no durable player identity to key on yet (#37).
    */
  sealed trait Outcome {

    /** Stable lower-case label used as a Prometheus dimension and as the JSON wire value. */
    def label: String
  }

  object Outcome {
    case object Won extends Outcome { val label = "won" }
    case object Draw extends Outcome { val label = "draw" }
    case object Forfeit extends Outcome { val label = "forfeit" }

    val all: List[Outcome] = List(Won, Draw, Forfeit)

    def fromLabel(s: String): Option[Outcome] = all.find(_.label == s)

    implicit val encoder: Encoder[Outcome] = Encoder.encodeString.contramap(_.label)
    implicit val decoder: Decoder[Outcome] =
      Decoder.decodeString.emap(s => fromLabel(s).toRight(s"Unknown Outcome: $s"))
  }

  /** A new game was created and started. */
  final case class GameStarted(gameId: GameId, gameType: GameType, playerCount: Int) extends GameAnalyticsEvent

  /** A move was successfully applied. `seq` is the 0-based ordinal of the move within the game. */
  final case class MoveMade(gameId: GameId, gameType: GameType, playerId: PlayerId, seq: Int) extends GameAnalyticsEvent

  /** A game reached a terminal state. `moveCount` is the total number of moves played. */
  final case class GameCompleted(gameId: GameId, gameType: GameType, outcome: Outcome, moveCount: Int)
      extends GameAnalyticsEvent

  /** A chat message was sent. `gameType` is `None` for a message sent in a lobby before the game starts. */
  final case class ChatSent(gameId: GameId, gameType: Option[GameType]) extends GameAnalyticsEvent

  /** Each variant is encoded as a JSON object with a `type` discriminator, mirroring the convention used by
    * [[com.andy327.server.http.json.JsonProtocol.playerEventEncoder]].
    */
  implicit val encoder: Encoder[GameAnalyticsEvent] = Encoder.instance {
    case GameStarted(gameId, gameType, playerCount) =>
      Json.obj(
        "type" -> "GameStarted".asJson,
        "gameId" -> gameId.asJson,
        "gameType" -> gameType.asJson,
        "playerCount" -> playerCount.asJson
      )
    case MoveMade(gameId, gameType, playerId, seq) =>
      Json.obj(
        "type" -> "MoveMade".asJson,
        "gameId" -> gameId.asJson,
        "gameType" -> gameType.asJson,
        "playerId" -> playerId.asJson,
        "seq" -> seq.asJson
      )
    case GameCompleted(gameId, gameType, outcome, moveCount) =>
      Json.obj(
        "type" -> "GameCompleted".asJson,
        "gameId" -> gameId.asJson,
        "gameType" -> gameType.asJson,
        "outcome" -> outcome.asJson,
        "moveCount" -> moveCount.asJson
      )
    case ChatSent(gameId, gameType) =>
      Json.obj(
        "type" -> "ChatSent".asJson,
        "gameId" -> gameId.asJson,
        "gameType" -> gameType.asJson
      )
  }

  implicit val decoder: Decoder[GameAnalyticsEvent] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case "GameStarted" =>
        for {
          gameId <- c.get[GameId]("gameId")
          gameType <- c.get[GameType]("gameType")
          playerCount <- c.get[Int]("playerCount")
        } yield GameStarted(gameId, gameType, playerCount)
      case "MoveMade" =>
        for {
          gameId <- c.get[GameId]("gameId")
          gameType <- c.get[GameType]("gameType")
          playerId <- c.get[PlayerId]("playerId")
          seq <- c.get[Int]("seq")
        } yield MoveMade(gameId, gameType, playerId, seq)
      case "GameCompleted" =>
        for {
          gameId <- c.get[GameId]("gameId")
          gameType <- c.get[GameType]("gameType")
          outcome <- c.get[Outcome]("outcome")
          moveCount <- c.get[Int]("moveCount")
        } yield GameCompleted(gameId, gameType, outcome, moveCount)
      case "ChatSent" =>
        for {
          gameId <- c.get[GameId]("gameId")
          gameType <- c.get[Option[GameType]]("gameType")
        } yield ChatSent(gameId, gameType)
      case other =>
        Left(io.circe.DecodingFailure(s"Unknown GameAnalyticsEvent type: $other", c.history))
    }
  }
}
