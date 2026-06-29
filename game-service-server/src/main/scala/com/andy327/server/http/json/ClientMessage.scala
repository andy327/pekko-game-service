package com.andy327.server.http.json

import io.circe.{Decoder, DecodingFailure}

import com.andy327.model.core.RoomId

/** A message a connected client sends to the server over the WebSocket.
  *
  * Inbound counterpart to the server-push `PlayerEvent`: decoded from JSON frames and
  * dispatched on a `type` discriminator field, so new client-initiated actions slot in as additional variants.
  */
sealed trait ClientMessage

object ClientMessage {

  /** Post `text` to the chat thread of the room identified by `roomId`. */
  final case class ChatSend(roomId: RoomId, text: String) extends ClientMessage

  /** Decodes a client frame by its `type` field; fails for unknown or malformed frames. */
  implicit val decoder: Decoder[ClientMessage] = Decoder.instance { cursor =>
    cursor.get[String]("type").flatMap {
      case "ChatSend" =>
        for {
          roomId <- cursor.get[RoomId]("roomId")
          text <- cursor.get[String]("text")
        } yield ChatSend(roomId, text)
      case other =>
        Left(DecodingFailure(s"Unknown client message type: $other", cursor.history))
    }
  }
}
