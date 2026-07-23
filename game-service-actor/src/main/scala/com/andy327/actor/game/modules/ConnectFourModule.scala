package com.andy327.actor.game.modules

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.ConnectFourMove
import com.andy327.actor.game.{GameOperation, GameProjection, GameView, MovePayload}
import com.andy327.model.connectfour.{ConnectFour, Drop}
import com.andy327.model.core.{GameError, PlayerId}

/** [[GameModule]] implementation for ConnectFour.
  *
  * Provides move decoding, operation-to-command mapping, and view projection for ConnectFour. Enables
  * [[core.GameManager]] and the HTTP routes to handle ConnectFour games without any game-specific logic.
  */
object ConnectFourModule extends GameModule[ConnectFour] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[ConnectFourMove].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.ConnectFourMove(col)) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Drop(col), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for ConnectFour: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def project(game: ConnectFour, viewer: Option[PlayerId]): GameView =
    GameProjection.project(game, viewer)
}
