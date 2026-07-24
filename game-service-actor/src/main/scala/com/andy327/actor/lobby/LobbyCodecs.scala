package com.andy327.actor.lobby

import java.util.UUID

import scala.util.Try

import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder, Json, KeyDecoder, KeyEncoder}

import com.andy327.actor.bot.BotDifficulty
import com.andy327.persistence.db.schema.GameTypeCodecs.gameTypeCodec

/** Circe codecs for lobby domain types, used by [[RedisLobbyRepository]] to serialize and deserialize
  * [[LobbyMetadata]] to and from JSON for Redis storage.
  *
  * `Map[PlayerId, Player]` uses UUID keys, which Circe cannot encode as JSON object keys by default. Explicit
  * `KeyEncoder` and `KeyDecoder` instances convert UUIDs to and from their string representations.
  */
object LobbyCodecs {

  implicit private val uuidKeyEncoder: KeyEncoder[UUID] = KeyEncoder.encodeKeyString.contramap(_.toString)
  implicit private val uuidKeyDecoder: KeyDecoder[UUID] =
    KeyDecoder.instance(s => Try(UUID.fromString(s)).toOption)

  implicit val playerCodec: Codec[Player] = deriveCodec[Player]

  implicit val statusCodec: Codec[GameLifecycleStatus] = Codec.from(
    Decoder.decodeString.emap {
      case "WaitingForPlayers" => Right(GameLifecycleStatus.WaitingForPlayers)
      case "ReadyToStart"      => Right(GameLifecycleStatus.ReadyToStart)
      case "InProgress"        => Right(GameLifecycleStatus.InProgress)
      case "Finished"          => Right(GameLifecycleStatus.Finished)
      case "Completed"         => Right(GameLifecycleStatus.Completed)
      case "Cancelled"         => Right(GameLifecycleStatus.Cancelled)
      case other               => Left(s"Unknown GameLifecycleStatus: $other")
    },
    Encoder.encodeString.contramap[GameLifecycleStatus] {
      case GameLifecycleStatus.WaitingForPlayers => "WaitingForPlayers"
      case GameLifecycleStatus.ReadyToStart      => "ReadyToStart"
      case GameLifecycleStatus.InProgress        => "InProgress"
      case GameLifecycleStatus.Finished          => "Finished"
      case GameLifecycleStatus.Completed         => "Completed"
      case GameLifecycleStatus.Cancelled         => "Cancelled"
    }
  )

  implicit val botDifficultyCodec: Codec[BotDifficulty] = Codec.from(
    Decoder.decodeString.emap(s => BotDifficulty.fromString(s).toRight(s"Unknown BotDifficulty: $s")),
    Encoder.encodeString.contramap[BotDifficulty](_.label)
  )

  private val derivedLobbyMetadataCodec: Codec[LobbyMetadata] = deriveCodec[LobbyMetadata]

  /** Decoding fills in an absent `bots` field rather than failing on it.
    *
    * Circe's derived decoders do not consult a case class's default values, so a room persisted before bot seats
    * existed would otherwise be unreadable — costing the room its name, host, and rematch on the first restart after
    * an upgrade. An older record simply has no bots, which is exactly what the empty map says.
    */
  implicit val lobbyMetadataCodec: Codec[LobbyMetadata] = Codec.from(
    Decoder.instance { cursor =>
      val filled = cursor.value.asObject
        .filterNot(_.contains("bots"))
        .map(obj => Json.fromJsonObject(obj.add("bots", Json.obj())).hcursor)
        .getOrElse(cursor)
      derivedLobbyMetadataCodec(filled)
    },
    derivedLobbyMetadataCodec
  )

  /** Serializes a [[LobbyMetadata]] instance to a compact JSON string. */
  def serialize(metadata: LobbyMetadata): String = metadata.asJson.noSpaces

  /** Deserializes a JSON string into a [[LobbyMetadata]] instance. */
  def deserialize(json: String): Either[Throwable, LobbyMetadata] =
    decode[LobbyMetadata](json).left.map(err => new Exception(err.getMessage))
}
