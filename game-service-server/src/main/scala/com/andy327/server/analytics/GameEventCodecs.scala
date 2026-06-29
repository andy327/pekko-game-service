package com.andy327.server.analytics

import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json}

import com.andy327.actor.events.GameEvent
import com.andy327.actor.events.GameEvent._
import com.andy327.model.core.{GameType, MatchId, PlayerId, RoomId}
import com.andy327.persistence.db.schema.GameTypeCodecs.gameTypeCodec

/** Circe codecs for `GameEvent` and its `GameEvent.Outcome`, used only at the edge: to put events
  * on the Redis `game-analytics` channel ([[RedisAnalyticsPublisher]]) and to read them back ([[AnalyticsConsumer]]).
  * Kept off the domain ADT so the actor layer can emit analytics events without depending on any wire format — the
  * same separation [[com.andy327.server.http.json.JsonProtocol]] gives
  * `PlayerEvent`.
  */
object GameEventCodecs {

  implicit val outcomeEncoder: Encoder[Outcome] = Encoder.encodeString.contramap(_.label)
  implicit val outcomeDecoder: Decoder[Outcome] =
    Decoder.decodeString.emap(s => Outcome.fromLabel(s).toRight(s"Unknown Outcome: $s"))

  /** Each variant is encoded as a JSON object with a `type` discriminator, mirroring the convention used by
    * [[com.andy327.server.http.json.JsonProtocol.playerEventEncoder]].
    */
  implicit val encoder: Encoder[GameEvent] = Encoder.instance {
    case GameStarted(matchId, gameType, playerCount) =>
      Json.obj(
        "type" -> "GameStarted".asJson,
        "matchId" -> matchId.asJson,
        "gameType" -> gameType.asJson,
        "playerCount" -> playerCount.asJson
      )
    case MoveMade(matchId, gameType, playerId, seq) =>
      Json.obj(
        "type" -> "MoveMade".asJson,
        "matchId" -> matchId.asJson,
        "gameType" -> gameType.asJson,
        "playerId" -> playerId.asJson,
        "seq" -> seq.asJson
      )
    case GameCompleted(matchId, gameType, outcome, moveCount) =>
      Json.obj(
        "type" -> "GameCompleted".asJson,
        "matchId" -> matchId.asJson,
        "gameType" -> gameType.asJson,
        "outcome" -> outcome.asJson,
        "moveCount" -> moveCount.asJson
      )
    case ChatSent(roomId, gameType) =>
      Json.obj(
        "type" -> "ChatSent".asJson,
        "roomId" -> roomId.asJson,
        "gameType" -> gameType.asJson
      )
  }

  implicit val decoder: Decoder[GameEvent] = Decoder.instance { c =>
    c.get[String]("type").flatMap {
      case "GameStarted" =>
        for {
          matchId <- c.get[MatchId]("matchId")
          gameType <- c.get[GameType]("gameType")
          playerCount <- c.get[Int]("playerCount")
        } yield GameStarted(matchId, gameType, playerCount)
      case "MoveMade" =>
        for {
          matchId <- c.get[MatchId]("matchId")
          gameType <- c.get[GameType]("gameType")
          playerId <- c.get[PlayerId]("playerId")
          seq <- c.get[Int]("seq")
        } yield MoveMade(matchId, gameType, playerId, seq)
      case "GameCompleted" =>
        for {
          matchId <- c.get[MatchId]("matchId")
          gameType <- c.get[GameType]("gameType")
          outcome <- c.get[Outcome]("outcome")
          moveCount <- c.get[Int]("moveCount")
        } yield GameCompleted(matchId, gameType, outcome, moveCount)
      case "ChatSent" =>
        for {
          roomId <- c.get[RoomId]("roomId")
          gameType <- c.get[Option[GameType]]("gameType")
        } yield ChatSent(roomId, gameType)
      case other =>
        Left(io.circe.DecodingFailure(s"Unknown GameEvent type: $other", c.history))
    }
  }
}
