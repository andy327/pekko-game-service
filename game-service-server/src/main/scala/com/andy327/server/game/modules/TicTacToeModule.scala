package com.andy327.server.game.modules

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.GameError
import com.andy327.model.tictactoe.{Location, TicTacToe}
import com.andy327.server.actors.core.{GameActor, TurnBasedGameActor}
import com.andy327.server.game.MovePayload.TicTacToeMove
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.{GameState, GameStateConverters}

/** [[GameModule]] implementation for TicTacToe.
  *
  * Provides move decoding, operation-to-command mapping, and game serialization for TicTacToe. Enables
  * [[com.andy327.server.actors.core.GameManager]] and the HTTP routes to handle TicTacToe games without
  * any game-specific logic.
  */
object TicTacToeModule extends GameModule[TicTacToe] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[TicTacToeMove].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.TicTacToeMove(row, col)) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Location(row, col), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for TicTacToe: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def serialize(game: TicTacToe): GameState = GameStateConverters.serializeGame(game)
}
