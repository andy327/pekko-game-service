package com.andy327.actor.game.modules

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.CheckersMove
import com.andy327.actor.game.{GameOperation, GameProjection, GameView, MovePayload}
import com.andy327.model.checkers.{Checkers, Move}
import com.andy327.model.core.{GameError, PlayerId}

/** [[GameModule]] implementation for Checkers.
  *
  * Provides move decoding, operation-to-command mapping, and view projection for Checkers. Enables
  * [[core.GameManager]] and the HTTP routes to handle Checkers games without any game-specific logic.
  */
object CheckersModule extends GameModule[Checkers] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[CheckersMove].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.CheckersMove(from, steps)) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Move(from, steps), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for Checkers: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def project(game: Checkers, viewer: Option[PlayerId]): GameView =
    GameProjection.project(game, viewer)
}
