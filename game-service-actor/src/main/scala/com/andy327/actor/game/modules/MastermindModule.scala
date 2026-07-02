package com.andy327.actor.game.modules

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.MastermindAction
import com.andy327.actor.game.{GameOperation, GameState, GameStateConverters, MovePayload}
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.mastermind.{Guess, Mastermind, Peg, SetCode}

/** [[GameModule]] implementation for Mastermind.
  *
  * Provides move decoding, operation-to-command mapping, and per-viewer serialization for Mastermind. The peg color
  * names carried in the payload are resolved to `Peg` values here; an unrecognized color is rejected as an `Unknown`
  * error so it never reaches the model.
  */
object MastermindModule extends GameModule[Mastermind] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[MastermindAction].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MastermindAction(action, pegs)) =>
      parsePegs(pegs).flatMap { parsed =>
        action.toLowerCase match {
          case "setcode" => Right(TurnBasedGameActor.MakeMove(playerId, SetCode(parsed), replyTo))
          case "guess"   => Right(TurnBasedGameActor.MakeMove(playerId, Guess(parsed), replyTo))
          case other     => Left(GameError.Unknown(s"Unknown Mastermind action: $other"))
        }
      }

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for Mastermind: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  /** Resolves each color name to a [[Peg]], failing with an `Unknown` error naming the first invalid color. */
  private def parsePegs(pegs: List[String]): Either[GameError, Vector[Peg]] =
    pegs.foldRight[Either[GameError, List[Peg]]](Right(Nil)) { (name, acc) =>
      for {
        rest <- acc
        peg <- Peg.fromName(name).toRight(GameError.Unknown(s"Invalid peg color: $name"))
      } yield peg :: rest
    }.map(_.toVector)

  override def serialize(game: Mastermind, viewer: Option[PlayerId]): GameState =
    GameStateConverters.serializeGame(game, viewer)
}
