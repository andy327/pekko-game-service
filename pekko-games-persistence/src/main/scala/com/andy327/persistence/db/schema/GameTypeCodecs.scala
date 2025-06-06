package com.andy327.persistence.db.schema

import io.circe.{Codec, Decoder, Encoder}
import io.circe.parser.decode
import io.circe.generic.auto._

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.TicTacToe

/**
 * Circe codecs and utilities for working with GameType and serialized Game state.
 *
 * This object provides:
 *  - A Codec[GameType] for serializing/deserializing GameType values as JSON strings
 *  - A `deserializeGame` function to deserialize stored game state JSON into a typed Game instance
 */
object GameTypeCodecs {
  implicit val gameTypeCodec: Codec[GameType] = Codec.from(
    Decoder.decodeString.emap {
      case "TicTacToe" => Right(GameType.TicTacToe)
      case other       => Left(s"Unknown GameType: $other")
    },
    Encoder.encodeString.contramap {
      case GameType.TicTacToe => "TicTacToe"
    }
  )

  /**
   * Deserializes a game state JSON string into a Game instance based on the provided GameType.
   */
  def deserializeGame(gameType: GameType, json: String): Either[Throwable, Game[_, _, _, _, _]] =
    gameType match {
      case GameType.TicTacToe => decode[TicTacToe](json).left.map(err => new Exception(err))
    }
}
