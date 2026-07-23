package com.andy327.actor.game.modules

import scala.util.Random

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.PigAction
import com.andy327.actor.game.{GameOperation, GameProjection, GameView, MovePayload}
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.pig.{Hold, Pig, Roll}

/** [[GameModule]] implementation for Pig.
  *
  * Provides move decoding, operation-to-command mapping, and view projection for Pig. Enables
  * [[core.GameManager]] and the HTTP routes to handle Pig games without any game-specific logic. The die is rolled
  * server-side here before dispatching to the model, keeping `Pig.play` a pure function.
  */
object PigModule extends GameModule[Pig] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[PigAction].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, PigAction("roll")) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Roll(Random.between(1, 7)), replyTo))

    case GameOperation.MakeMove(playerId, PigAction("hold")) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Hold, replyTo))

    case GameOperation.MakeMove(_, PigAction(unknown)) =>
      Left(GameError.Unknown(s"Unknown Pig action: $unknown"))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for Pig: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def project(game: Pig, viewer: Option[PlayerId]): GameView =
    GameProjection.project(game, viewer)
}
