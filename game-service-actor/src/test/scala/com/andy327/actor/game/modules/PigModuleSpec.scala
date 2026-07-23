package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{GameOperation, GameView, MovePayload, PigView}
import com.andy327.actor.lobby.Player
import com.andy327.actor.pig.PigActor
import com.andy327.model.core.GameError
import com.andy327.model.pig.{Hold, Pig, Roll}

class PigModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "PigModule" should {
    "successfully decode a roll action JSON" in {
      val json = """{"action":"roll"}"""
      decode[MovePayload](json)(PigModule.moveDecoder) shouldBe Right(MovePayload.PigAction("roll"))
    }

    "successfully decode a hold action JSON" in {
      val json = """{"action":"hold"}"""
      decode[MovePayload](json)(PigModule.moveDecoder) shouldBe Right(MovePayload.PigAction("hold"))
    }

    "fail to decode an invalid Pig move JSON" in {
      val json = """{"bad":"data"}"""
      decode[MovePayload](json)(PigModule.moveDecoder).isLeft shouldBe true
    }

    "convert a roll GameOperation.MakeMove to a Roll GameCommand with a valid die value" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val result = PigModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.PigAction("roll")),
        replyProbe.ref
      )

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, Roll(result), reply)) =>
          playerId shouldBe alice.id
          result should ((be >= 1).and(be <= 6))
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert a hold GameOperation.MakeMove to a Hold GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val result = PigModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.PigAction("hold")),
        replyProbe.ref
      )

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, Hold, reply)) =>
          playerId shouldBe alice.id
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "return an error for an unknown action string" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val Left(err) = PigModule.toGameCommand(
        GameOperation.MakeMove(alice.id, MovePayload.PigAction("bad")),
        replyProbe.ref
      )

      err.message should include("Unknown Pig action: bad")
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val result = PigModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()

      val result = PigActor.subscribeCommand(playerProbe.ref, playerId)

      result shouldBe TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a Pig game to PigView with correct initial field values" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = Pig.newGame(Seq(alice.id, bob.id))
      val state = PigModule.project(game, None)
      state shouldBe PigView(
        scores = Vector(0, 0),
        currentPlayer = 0,
        turnScore = 0,
        lastRoll = None,
        winner = None,
        viewerSeat = None,
        legalMoves = Nil // a spectator holds no seat, so has no move to make
      )
    }

    "offer roll and hold to the player whose turn it is" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = Pig.newGame(Seq(alice.id, bob.id)) // seat 0 (alice) leads
      val state = PigModule.project(game, Some(alice.id)).asInstanceOf[PigView]
      state.legalMoves shouldBe List(MovePayload.PigAction("roll"), MovePayload.PigAction("hold"))
    }

    "offer no moves to a player waiting on their opponent" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = Pig.newGame(Seq(alice.id, bob.id)) // seat 0 (alice) leads, so bob is waiting
      PigModule.project(game, Some(bob.id)).asInstanceOf[PigView].legalMoves shouldBe empty
    }

    "offer no moves in PigView once the game has ended" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = Pig.newGame(Seq(alice.id, bob.id))
      val won = game.copy(winner = Some(0)) // alice won, and it is still nominally her seat to act
      PigModule.project(won, Some(alice.id)).asInstanceOf[PigView].legalMoves shouldBe empty
    }

    "set winner in PigView when the game has ended" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = Pig.newGame(Seq(alice.id, bob.id))
      val won = game.copy(winner = Some(0))
      val state = PigModule.project(won, None).asInstanceOf[PigView]
      state.winner shouldBe Some(0)
    }

    "set viewerSeat when serializing for a known player" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = Pig.newGame(Seq(alice.id, bob.id))
      val state = PigModule.project(game, Some(alice.id)).asInstanceOf[PigView]
      state.viewerSeat shouldBe Some(0)
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val unsupportedMove = null.asInstanceOf[MovePayload]

      val Left(err) = PigModule.toGameCommand(
        GameOperation.MakeMove(alice.id, unsupportedMove),
        replyProbe.ref
      )

      err.message should include("Unsupported move type for Pig")
    }
  }
}
