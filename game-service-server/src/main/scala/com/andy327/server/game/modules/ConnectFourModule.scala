package com.andy327.server.game.modules

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.connectfour.{ConnectFour, Drop}
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.server.actors.core.{GameActor, TurnBasedGameActor}
import com.andy327.server.game.MovePayload.ConnectFourMove
import com.andy327.server.game.{GameOperation, GameState, GameStateConverters, MovePayload}

/** [[GameModule]] implementation for ConnectFour.
  *
  * Provides move decoding, operation-to-command mapping, and game serialization for ConnectFour. Enables
  * [[com.andy327.server.actors.core.GameManager]] and the HTTP routes to handle ConnectFour games without
  * any game-specific logic.
  */
object ConnectFourModule extends GameModule[ConnectFour] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[ConnectFourMove].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.ConnectFourMove(col)) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Drop(col), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for ConnectFour: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def serialize(game: ConnectFour, viewer: Option[PlayerId]): GameState =
    GameStateConverters.serializeGame(game, viewer)
}
