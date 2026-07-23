package com.andy327.actor.game.modules

import scala.util.Random

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.HoldEmAction
import com.andy327.actor.game.{GameOperation, GameProjection, GameView, MovePayload}
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.holdem.Action.{Bet, Call, Check, Fold, Raise}
import com.andy327.model.holdem.{Action, Card, HoldEmMove, TexasHoldEm}

/** [[GameModule]] implementation for Texas Hold 'Em.
  *
  * Provides move decoding, operation-to-command mapping, and per-viewer projection. Every action carries a freshly
  * shuffled deck produced here — server-side — because any action can end a hand and start the next one; the pure model
  * deals the next hand from it and ignores it otherwise, so `TexasHoldEm.play` stays a pure function. Bet and raise
  * sizing is validated by the model, which rejects an illegal amount with its own errors.
  */
object TexasHoldEmModule extends GameModule[TexasHoldEm] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[HoldEmAction].widen

  /** A freshly shuffled 52-card deck. Used for the opening deal and carried on every move for the next hand. */
  def shuffledDeck(): List[Card] = Random.shuffle(Card.deck)

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, HoldEmAction(action, amount)) =>
      def command(a: Action): Either[GameError, GameActor.GameCommand] =
        Right(TurnBasedGameActor.MakeMove(playerId, HoldEmMove(a, shuffledDeck()), replyTo))

      def sized(make: Int => Action): Either[GameError, GameActor.GameCommand] =
        amount match {
          case Some(a) => command(make(a))
          case None    => Left(GameError.Unknown(s"A Texas Hold 'Em $action requires an amount."))
        }

      action.toLowerCase match {
        case "fold"  => command(Fold)
        case "check" => command(Check)
        case "call"  => command(Call)
        case "bet"   => sized(Bet(_))
        case "raise" => sized(Raise(_))
        case other   => Left(GameError.Unknown(s"Unknown Texas Hold 'Em action: $other"))
      }

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for Texas Hold 'Em: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def project(game: TexasHoldEm, viewer: Option[PlayerId]): GameView =
    GameProjection.project(game, viewer)
}
