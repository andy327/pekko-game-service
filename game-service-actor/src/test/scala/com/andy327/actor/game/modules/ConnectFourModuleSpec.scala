package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.connectfour.ConnectFourActor
import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{GameOperation, GameView, GridGameView, MovePayload}
import com.andy327.actor.lobby.Player
import com.andy327.model.connectfour.{ConnectFour, Drop}
import com.andy327.model.core.GameError

class ConnectFourModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "ConnectFourModule" should {
    "successfully decode a valid ConnectFour move JSON" in {
      val json = """{"col":3}"""
      decode[MovePayload](json)(ConnectFourModule.moveDecoder) shouldBe Right(MovePayload.ConnectFourMove(3))
    }

    "fail to decode an invalid ConnectFour move JSON" in {
      val json = """{"bad":"data"}"""
      decode[MovePayload](json)(ConnectFourModule.moveDecoder).isLeft shouldBe true
    }

    "convert a valid GameOperation.MakeMove to a GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val move = MovePayload.ConnectFourMove(3)

      val result = ConnectFourModule.toGameCommand(GameOperation.MakeMove(alice.id, move), replyProbe.ref)

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, drop, reply)) =>
          playerId shouldBe alice.id
          drop shouldBe Drop(3)
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()

      val result = ConnectFourModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()

      val result = ConnectFourActor.subscribeCommand(playerProbe.ref, playerId)

      result shouldBe TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a ConnectFour game to GridGameView" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = ConnectFour.empty(alice.id, bob.id)
      ConnectFourModule.project(game, None) shouldBe a[GridGameView]
    }

    "offer the player to act their own moves, and nobody else any" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = ConnectFour.empty(alice.id, bob.id) // Red (alice) leads

      val toAct = ConnectFourModule.project(game, Some(alice.id)).asInstanceOf[GridGameView]
      toAct.legalMoves should have size ConnectFour.Cols.toLong

      ConnectFourModule.project(game, Some(bob.id)).asInstanceOf[GridGameView].legalMoves shouldBe empty
      ConnectFourModule.project(game, None).asInstanceOf[GridGameView].legalMoves shouldBe empty // spectator
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val unsupportedMove = null.asInstanceOf[MovePayload]

      val result = ConnectFourModule.toGameCommand(
        GameOperation.MakeMove(alice.id, unsupportedMove),
        replyProbe.ref
      )

      val Left(err) = result
      err.message should include("Unsupported move type for ConnectFour")
    }
  }
}
