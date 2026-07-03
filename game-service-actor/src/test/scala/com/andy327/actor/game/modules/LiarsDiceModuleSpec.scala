package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{GameOperation, GameRegistry, GameState, LiarsDiceState, MovePayload}
import com.andy327.actor.liarsdice.LiarsDiceActor
import com.andy327.actor.lobby.Player
import com.andy327.model.core.{GameError, GameType}
import com.andy327.model.liarsdice.{Bid, Challenge, LiarsDice, MakeBid}

class LiarsDiceModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "LiarsDiceModule" should {
    "decode a numbered bid action JSON" in {
      val json = """{"action":"bid","quantity":3,"face":4}"""
      decode[MovePayload](json)(LiarsDiceModule.moveDecoder) shouldBe
        Right(MovePayload.LiarsDiceAction("bid", Some(3), Some(4)))
    }

    "decode a wild ones bid (no face) action JSON" in {
      val json = """{"action":"bid","quantity":2}"""
      decode[MovePayload](json)(LiarsDiceModule.moveDecoder) shouldBe
        Right(MovePayload.LiarsDiceAction("bid", Some(2), None))
    }

    "decode a challenge action JSON" in {
      val json = """{"action":"challenge"}"""
      decode[MovePayload](json)(LiarsDiceModule.moveDecoder) shouldBe
        Right(MovePayload.LiarsDiceAction("challenge", None, None))
    }

    "fail to decode an invalid Liar's Dice move JSON" in {
      decode[MovePayload]("""{"bad":"data"}""")(LiarsDiceModule.moveDecoder).isLeft shouldBe true
    }

    "convert a numbered bid MakeMove to a MakeBid GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = LiarsDiceModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.LiarsDiceAction("bid", Some(3), Some(4))),
        replyProbe.ref
      )

      result shouldBe Right(TurnBasedGameActor.MakeMove(alice.id, MakeBid(Bid(3, Some(4))), replyProbe.ref))
    }

    "convert a wild ones bid MakeMove to a MakeBid GameCommand with no face" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = LiarsDiceModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.LiarsDiceAction("bid", Some(2), None)),
        replyProbe.ref
      )

      result shouldBe Right(TurnBasedGameActor.MakeMove(alice.id, MakeBid(Bid(2, None)), replyProbe.ref))
    }

    "reject a bid with no quantity" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val Left(err) = LiarsDiceModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.LiarsDiceAction("bid", None, None)),
        replyProbe.ref
      )

      err.message should include("requires a quantity")
    }

    "convert a challenge MakeMove to a Challenge GameCommand carrying a full server-rolled pool" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = LiarsDiceModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.LiarsDiceAction("challenge", None, None)),
        replyProbe.ref
      )

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, Challenge(pool), reply)) =>
          playerId shouldBe alice.id
          pool.size shouldBe LiarsDice.MaxTotalDice
          pool.forall(d => d >= 1 && d <= 6) shouldBe true
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "return an error for an unknown action string" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val Left(err) = LiarsDiceModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.LiarsDiceAction("bad", None, None)),
        replyProbe.ref
      )

      err.message should include("Unknown Liar's Dice action: bad")
    }

    "return error when passing an unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val Left(err) = LiarsDiceModule.toGameCommand(
        GameOperation.MakeMove(alice.id, null.asInstanceOf[MovePayload]),
        replyProbe.ref
      )

      err.message should include("Unsupported move type for Liar's Dice")
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      LiarsDiceModule.toGameCommand(GameOperation.GetState, replyProbe.ref) shouldBe
        Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()
      LiarsDiceActor.subscribeCommand(playerProbe.ref, playerId) shouldBe
        TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a Liar's Dice game to a LiarsDiceState for the requesting player" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = LiarsDice.newGame(Seq(alice.id, bob.id), List.fill(LiarsDice.MaxTotalDice)(4))
      val state = LiarsDiceModule.serialize(game, Some(alice.id)).asInstanceOf[LiarsDiceState]
      state.viewerSeat shouldBe Some("P1")
      state.dice shouldBe Some(List.fill(5)(4))
      state.diceCounts shouldBe Map("P1" -> 5, "P2" -> 5)
      state.currentPlayer shouldBe "P1"
      state.currentBid shouldBe None
    }

    "expose the Liar's Dice bundle through the GameRegistry" in {
      val bundle = GameRegistry.forType(GameType.LiarsDice)
      bundle.module shouldBe LiarsDiceModule
      bundle.actor shouldBe LiarsDiceActor
    }
  }
}
