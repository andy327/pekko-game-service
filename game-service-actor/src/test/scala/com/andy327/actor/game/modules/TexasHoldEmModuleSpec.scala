package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{GameOperation, GameRegistry, GameView, HoldEmView, MovePayload}
import com.andy327.actor.lobby.Player
import com.andy327.actor.texasholdem.TexasHoldEmActor
import com.andy327.model.core.{GameError, GameType}
import com.andy327.model.holdem.Action.{Bet, Call, Check, Fold, Raise}
import com.andy327.model.holdem.{Action, Card, HoldEmMove, TexasHoldEm}

class TexasHoldEmModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "TexasHoldEmModule" should {
    "decode a fold action JSON" in {
      decode[MovePayload]("""{"action":"fold"}""")(TexasHoldEmModule.moveDecoder) shouldBe
        Right(MovePayload.HoldEmAction("fold", None))
    }

    "decode a bet action JSON with an amount" in {
      decode[MovePayload]("""{"action":"bet","amount":50}""")(TexasHoldEmModule.moveDecoder) shouldBe
        Right(MovePayload.HoldEmAction("bet", Some(50)))
    }

    "fail to decode an invalid Texas Hold 'Em move JSON" in {
      decode[MovePayload]("""{"bad":"data"}""")(TexasHoldEmModule.moveDecoder).isLeft shouldBe true
    }

    "convert a fold MakeMove to a Fold GameCommand carrying a full shuffled deck" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      TexasHoldEmModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.HoldEmAction("fold", None)),
        replyProbe.ref
      ) match {
        case Right(TurnBasedGameActor.MakeMove(playerId, HoldEmMove(action, deck), reply)) =>
          playerId shouldBe alice.id
          action shouldBe Fold
          deck.size shouldBe 52
          deck.toSet.size shouldBe 52 // a real, distinct shuffle
          reply shouldBe replyProbe.ref
        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert a bet MakeMove to a Bet GameCommand with the amount" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      TexasHoldEmModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.HoldEmAction("bet", Some(75))),
        replyProbe.ref
      ) match {
        case Right(TurnBasedGameActor.MakeMove(_, HoldEmMove(action, _), _)) => action shouldBe Bet(75)
        case other                                                           => fail(s"Unexpected result: $other")
      }
    }

    "convert check, call, and raise actions to their moves" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      def action(payload: MovePayload.HoldEmAction): Action =
        TexasHoldEmModule.toGameCommand(GameOperation.MakeMove(alice.id, payload), replyProbe.ref) match {
          case Right(TurnBasedGameActor.MakeMove(_, HoldEmMove(a, _), _)) => a
          case other                                                      => fail(s"Unexpected result: $other")
        }
      action(MovePayload.HoldEmAction("check", None)) shouldBe Check
      action(MovePayload.HoldEmAction("call", None)) shouldBe Call
      action(MovePayload.HoldEmAction("raise", Some(40))) shouldBe Raise(40)
    }

    "reject a bet with no amount" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val Left(err) = TexasHoldEmModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.HoldEmAction("bet", None)),
        replyProbe.ref
      )
      err.message should include("requires an amount")
    }

    "return an error for an unknown action string" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val Left(err) = TexasHoldEmModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.HoldEmAction("bad", None)),
        replyProbe.ref
      )
      err.message should include("Unknown Texas Hold 'Em action: bad")
    }

    "return error when passing an unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val Left(err) = TexasHoldEmModule.toGameCommand(
        GameOperation.MakeMove(alice.id, null.asInstanceOf[MovePayload]),
        replyProbe.ref
      )
      err.message should include("Unsupported move type for Texas Hold 'Em")
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      TexasHoldEmModule.toGameCommand(GameOperation.GetState, replyProbe.ref) shouldBe
        Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()
      TexasHoldEmActor.subscribeCommand(playerProbe.ref, playerId) shouldBe
        TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a Texas Hold 'Em game to a HoldEmView for the requesting player" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = TexasHoldEm.newGame(Seq(alice.id, bob.id), Card.deck)
      val state = TexasHoldEmModule.project(game, Some(alice.id)).asInstanceOf[HoldEmView]
      state.viewerSeat shouldBe Some(0)
      state.holeCards.map(_.size) shouldBe Some(2)
      state.seats should have size 2
      state.currentPlayer shouldBe 0
      state.pot shouldBe 15
    }

    "expose the Texas Hold 'Em bundle through the GameRegistry" in {
      val bundle = GameRegistry.forType(GameType.TexasHoldEm)
      bundle.module shouldBe TexasHoldEmModule
      bundle.actor shouldBe TexasHoldEmActor
    }
  }
}
