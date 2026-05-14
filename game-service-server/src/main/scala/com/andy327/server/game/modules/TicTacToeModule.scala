package com.andy327.server.game.modules

import scala.util.Try

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef
import spray.json._

import com.andy327.model.core.{Game, GameError}
import com.andy327.model.tictactoe.{Location, TicTacToe}
import com.andy327.server.actors.core.{GameActor, PlayerActor}
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.game.MovePayload.TicTacToeMove
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.{GameState, GameStateConverters, JsonProtocol, TicTacToeMoveRequest, TicTacToeState}

import TicTacToeState._

/** GameModule implementation for TicTacToe.
  *
  * This module provides game-specific logic for:
  *   - Parsing client-submitted moves into MovePayloads
  *   - Mapping game-agnostic operations into TicTacToe-specific actor messages
  *
  * It enables TicTacToe to be managed and routed in a game-agnostic way by the GameManager and HTTP routes.
  */
object TicTacToeModule extends GameModule {
  import JsonProtocol._

  override val moveDecoder: Decoder[MovePayload] = Decoder[TicTacToeMove].widen

  /** Parses a TicTacToeMoveRequest from JSON and converts it to a MovePayload.
    *
    * @param json the raw JSON value submitted by the client
    * @return `Right(TicTacToeMove)` on success, or `Left(errorMessage)` if the JSON is malformed
    */
  override def parseMove(json: JsValue): Either[String, MovePayload] =
    Try(json.convertTo[TicTacToeMoveRequest])
      .map(req => MovePayload.TicTacToeMove(req.row, req.col))
      .toEither
      .left
      .map(_.getMessage)

  /** Converts a generic GameOperation into a TicTacToe-specific GameCommand.
    *
    * @param op the game-agnostic operation from the HTTP layer
    * @param replyTo the actor that should receive the operation result
    * @return `Right(command)` ready to send to TicTacToeActor, or `Left(GameError)` if the payload type is wrong
    */
  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.TicTacToeMove(row, col)) =>
      Right(TicTacToeActor.MakeMove(playerId, Location(row, col), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for TicTacToe: $name"))

    case GameOperation.GetState =>
      Right(TicTacToeActor.GetState(replyTo))
  }

  override def subscribeCommand(playerRef: ActorRef[PlayerActor.Command]): GameActor.GameCommand =
    TicTacToeActor.Subscribe(playerRef)

  /** Serialises a TicTacToe game model into a [[com.andy327.server.http.json.GameState]] for HTTP/WebSocket delivery.
    *
    * @param game the game model to serialise; must be a `TicTacToe` instance
    * @return the corresponding [[com.andy327.server.http.json.TicTacToeState]]
    * @throws java.lang.IllegalArgumentException if `game` is not a TicTacToe
    */
  override def serialize(game: Game[_, _, _, _, _]): GameState = game match {
    case ttt: TicTacToe => GameStateConverters.serializeGame(ttt)
    case other          =>
      throw new IllegalArgumentException(
        s"TicTacToeModule.serialize expected TicTacToe, got: ${other.getClass.getSimpleName}"
      )
  }
}
