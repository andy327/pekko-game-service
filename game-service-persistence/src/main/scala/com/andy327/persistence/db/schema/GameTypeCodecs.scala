package com.andy327.persistence.db.schema

import io.circe.generic.semiauto.deriveCodec
import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Codec, Decoder, Encoder}

import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Seat, Ship}
import com.andy327.model.connectfour.{ConnectFour, Mark => ConnectFourMark, Red, Yellow}
import com.andy327.model.core.{Game, GameType}
import com.andy327.model.liarsdice.{Bid, LiarsDice, Reveal, StandingBid}
import com.andy327.model.mastermind.{Attempt, Codebreaker, Codemaker, Feedback, Mastermind, Peg, Role}
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.{Mark, O, TicTacToe, X}

/** Circe codecs and utilities for working with GameType and serialized Game state.
  *
  * This object provides:
  *   - A Codec[GameType] for serializing/deserializing GameType values as JSON strings
  *   - A `deserializeGame` function to deserialize stored game state JSON into a typed Game instance
  */
object GameTypeCodecs {

  implicit val gameTypeCodec: Codec[GameType] = Codec.from(
    Decoder.decodeString.emap {
      case "TicTacToe"   => Right(GameType.TicTacToe)
      case "ConnectFour" => Right(GameType.ConnectFour)
      case "Battleship"  => Right(GameType.Battleship)
      case "Pig"         => Right(GameType.Pig)
      case "Mastermind"  => Right(GameType.Mastermind)
      case "LiarsDice"   => Right(GameType.LiarsDice)
      case other         => Left(s"Unknown GameType: $other")
    },
    Encoder.encodeString.contramap[GameType] {
      case GameType.TicTacToe   => "TicTacToe"
      case GameType.ConnectFour => "ConnectFour"
      case GameType.Battleship  => "Battleship"
      case GameType.Pig         => "Pig"
      case GameType.Mastermind  => "Mastermind"
      case GameType.LiarsDice   => "LiarsDice"
    }
  )

  implicit val ticTacToeMarkCodec: Codec[Mark] = Codec.from(
    Decoder.decodeString.emap {
      case "X"   => Right(X)
      case "O"   => Right(O)
      case other => Left(s"Invalid Mark: expected 'X' or 'O', got '$other'")
    },
    Encoder.encodeString.contramap[Mark](_.symbol)
  )

  implicit val ticTacToeCodec: Codec[TicTacToe] = deriveCodec[TicTacToe]

  implicit val connectFourMarkCodec: Codec[ConnectFourMark] = Codec.from(
    Decoder.decodeString.emap {
      case "R"   => Right(Red)
      case "Y"   => Right(Yellow)
      case other => Left(s"Invalid ConnectFour Mark: expected 'R' or 'Y', got '$other'")
    },
    Encoder.encodeString.contramap[ConnectFourMark](_.symbol)
  )

  implicit val connectFourCodec: Codec[ConnectFour] = deriveCodec[ConnectFour]

  implicit val battleshipSeatCodec: Codec[Seat] = Codec.from(
    Decoder.decodeString.emap {
      case "P1"  => Right(Player1)
      case "P2"  => Right(Player2)
      case other => Left(s"Invalid Seat: expected 'P1' or 'P2', got '$other'")
    },
    Encoder.encodeString.contramap[Seat](_.symbol)
  )

  // Codecs are declared in dependency order so each is in scope for the deriveCodec that needs it.
  implicit val coordCodec: Codec[Coord] = deriveCodec[Coord]
  implicit val shipCodec: Codec[Ship] = deriveCodec[Ship]
  implicit val playerBoardCodec: Codec[PlayerBoard] = deriveCodec[PlayerBoard]
  implicit val battleshipCodec: Codec[Battleship] = deriveCodec[Battleship]
  implicit val pigCodec: Codec[Pig] = deriveCodec[Pig]

  implicit val pegCodec: Codec[Peg] = Codec.from(
    Decoder.decodeString.emap(s => Peg.fromName(s).toRight(s"Invalid Peg: $s")),
    Encoder.encodeString.contramap[Peg](_.name)
  )

  implicit val roleCodec: Codec[Role] = Codec.from(
    Decoder.decodeString.emap {
      case "codemaker"   => Right(Codemaker)
      case "codebreaker" => Right(Codebreaker)
      case other         => Left(s"Invalid Role: expected 'codemaker' or 'codebreaker', got '$other'")
    },
    Encoder.encodeString.contramap[Role](_.label)
  )

  // Declared in dependency order so each is in scope for the deriveCodec that needs it.
  implicit val feedbackCodec: Codec[Feedback] = deriveCodec[Feedback]
  implicit val attemptCodec: Codec[Attempt] = deriveCodec[Attempt]
  implicit val mastermindCodec: Codec[Mastermind] = deriveCodec[Mastermind]

  // Declared in dependency order so each is in scope for the deriveCodec that needs it. A Bid's optional `face`
  // (absent for a wild "ones" bid) round-trips as a nullable JSON field.
  implicit val bidCodec: Codec[Bid] = deriveCodec[Bid]
  implicit val standingBidCodec: Codec[StandingBid] = deriveCodec[StandingBid]
  implicit val revealCodec: Codec[Reveal] = deriveCodec[Reveal]
  implicit val liarsDiceCodec: Codec[LiarsDice] = deriveCodec[LiarsDice]

  /** Serializes a game instance to a JSON string using the codec for the given GameType. */
  def serializeGame(gameType: GameType, game: Game[_, _, _, _, _]): String = gameType match {
    case GameType.TicTacToe   => game.asInstanceOf[TicTacToe].asJson.noSpaces
    case GameType.ConnectFour => game.asInstanceOf[ConnectFour].asJson.noSpaces
    case GameType.Battleship  => game.asInstanceOf[Battleship].asJson.noSpaces
    case GameType.Pig         => game.asInstanceOf[Pig].asJson.noSpaces
    case GameType.Mastermind  => game.asInstanceOf[Mastermind].asJson.noSpaces
    case GameType.LiarsDice   => game.asInstanceOf[LiarsDice].asJson.noSpaces
  }

  /** Deserializes a game state JSON string into a Game instance based on the provided GameType. */
  def deserializeGame(gameType: GameType, json: String): Either[Throwable, Game[_, _, _, _, _]] =
    gameType match {
      case GameType.TicTacToe   => decode[TicTacToe](json).left.map(err => new Exception(err))
      case GameType.ConnectFour => decode[ConnectFour](json).left.map(err => new Exception(err))
      case GameType.Battleship  => decode[Battleship](json).left.map(err => new Exception(err))
      case GameType.Pig         => decode[Pig](json).left.map(err => new Exception(err))
      case GameType.Mastermind  => decode[Mastermind](json).left.map(err => new Exception(err))
      case GameType.LiarsDice   => decode[LiarsDice](json).left.map(err => new Exception(err))
    }
}
