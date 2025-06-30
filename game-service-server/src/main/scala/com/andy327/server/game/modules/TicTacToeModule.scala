package com.andy327.server.game.modules

import scala.util.Try

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef
import spray.json._

import com.andy327.model.core.GameError
import com.andy327.model.tictactoe.Location
import com.andy327.server.actors.core.GameActor
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.game.MovePayload.TicTacToeMove
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.{GameState, JsonProtocol, TicTacToeMoveRequest}

/**
 * GameModule implementation for TicTacToe.
 *
 * This module provides game-specific logic for:
 *  - Parsing client-submitted moves into MovePayloads
 *  - Mapping game-agnostic operations into TicTacToe-specific actor messages
 *
 * It enables TicTacToe to be managed and routed in a game-agnostic way by the GameManager and HTTP routes.
 */
object TicTacToeModule extends GameModule {
  import JsonProtocol._

  override val moveDecoder: Decoder[MovePayload] = Decoder[TicTacToeMove].widen

  /**
   * Parses a TicTacToeMoveRequest from JSON and converts it to a MovePayload.
   * If parsing fails, returns a Left with an error message.
   */
  override def parseMove(json: JsValue): Either[String, MovePayload] =
    Try(json.convertTo[TicTacToeMoveRequest])
      .map(req => MovePayload.TicTacToeMove(req.row, req.col))
      .toEither
      .left
      .map(_.getMessage)

  /**
   * Converts a generic GameOperation into a TicTacToe-specific GameCommand.
   * Returns an error if the MovePayload is not valid for TicTacToe.
   */
  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.TicTacToeMove(row, col)) =>
      Right(TicTacToeActor.MakeMove(playerId, Location(row, col), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      Left(GameError.Unknown(s"Unsupported move type for TicTacToe: ${otherMove.getClass.getSimpleName}"))

    case GameOperation.GetState =>
      Right(TicTacToeActor.GetState(replyTo))
  }
}
