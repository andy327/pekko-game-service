package com.andy327.server.analytics

import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

import com.andy327.model.core.{GameId, GameType, PlayerId}
import com.andy327.persistence.db.schema.GameTypeCodecs.gameTypeCodec
import com.andy327.server.analytics.GameAnalyticsEvent._

/** Circe codecs for [[GameAnalyticsEvent]] and its [[GameAnalyticsEvent.Outcome]], used only at the edge: to put events
  * on the Redis `game-analytics` channel ([[RedisAnalyticsPublisher]]) and to read them back ([[AnalyticsConsumer]]).
  * Kept off the domain ADT so the actor layer can emit analytics events without depending on any wire format — the
  * same separation [[com.andy327.server.http.json.JsonProtocol]] gives
  * [[com.andy327.server.actors.core.PlayerEvent]].
  */
object GameAnalyticsCodecs {

  implicit val outcomeEncoder: Encoder[Outcome] = Encoder.encodeString.contramap(_.label)
  implicit val outcomeDecoder: Decoder[Outcome] =
    Decoder.decodeString.emap(s => Outcome.fromLabel(s).toRight(s"Unknown Outcome: $s"))

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
