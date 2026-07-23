package com.andy327.actor.game.modules

import scala.util.Random

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.LiarsDiceAction
import com.andy327.actor.game.{GameOperation, GameProjection, GameView, MovePayload}
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.liarsdice.{Bid, Challenge, LiarsDice, MakeBid}

/** [[GameModule]] implementation for Liar's Dice.
  *
  * Provides move decoding, operation-to-command mapping, and per-viewer projection for Liar's Dice. Dice are rolled
  * server-side here — a challenge carries a fresh pool the pure model deals to the surviving hands for the next round —
  * so `LiarsDice.play` stays a pure function. Bid well-formedness (a positive quantity, a face of 2–6) is left to the
  * model, which rejects a malformed bid with its own `InvalidBid` error.
  */
object LiarsDiceModule extends GameModule[LiarsDice] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[LiarsDiceAction].widen

  /** A flat pool of freshly rolled dice, sized to the most dice that can ever be on the table. The model deals out only
    * the prefix each surviving hand needs; the surplus is harmless. Used for both the opening deal and each re-roll.
    */
  def rollPool(): List[Int] = List.fill(LiarsDice.MaxTotalDice)(Random.between(1, 7))

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, LiarsDiceAction(action, quantity, face)) =>
      action.toLowerCase match {
        case "bid" =>
          quantity match {
            case Some(q) => Right(TurnBasedGameActor.MakeMove(playerId, MakeBid(Bid(q, face)), replyTo))
            case None    => Left(GameError.Unknown("A Liar's Dice bid requires a quantity."))
          }
        case "challenge" => Right(TurnBasedGameActor.MakeMove(playerId, Challenge(rollPool()), replyTo))
        case other       => Left(GameError.Unknown(s"Unknown Liar's Dice action: $other"))
      }

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for Liar's Dice: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def project(game: LiarsDice, viewer: Option[PlayerId]): GameView =
    GameProjection.project(game, viewer)
}
