package com.andy327.actor.chat

import io.circe.Codec
import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._

import com.andy327.actor.core.PlayerEvent

/** Circe codec for chat messages, used by [[RedisChatRepository]] to store and reload
  * [[com.andy327.actor.core.PlayerEvent.ChatMessage]]s as JSON in Redis, and re-exported by the HTTP layer to encode
  * the `GET /chat` history response.
  *
  * This is the plain record form — the chat fields with no envelope. It is intentionally distinct from the tagged
  * `{"type":"ChatMessage", ...}` form that `JsonProtocol.playerEventEncoder` uses for
  * live WebSocket push: the live event is one variant of a discriminated `PlayerEvent` stream, whereas a
  * stored/returned history record is always a chat message and needs no discriminator. Both carry the same fields.
  */
object ChatCodecs {

  implicit val chatMessageCodec: Codec[PlayerEvent.ChatMessage] = deriveCodec[PlayerEvent.ChatMessage]

  /** Serializes a chat message to a compact JSON string for Redis storage. */
  def serialize(message: PlayerEvent.ChatMessage): String = message.asJson.noSpaces

  /** Deserializes a JSON string back into a chat message. */
  def deserialize(json: String): Either[Throwable, PlayerEvent.ChatMessage] =
    decode[PlayerEvent.ChatMessage](json).left.map(err => new Exception(err.getMessage))
}
