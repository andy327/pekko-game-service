package com.andy327.server.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.connectfour.{ConnectFour, Drop}
import com.andy327.model.core.GameError
import com.andy327.server.actors.connectfour.ConnectFourActor
import com.andy327.server.actors.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.{GameState, GridGameState}
import com.andy327.server.lobby.Player

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
      val replyProbe = TestProbe[Either[GameError, GameState]]()
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
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = ConnectFourModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()

      val result = ConnectFourActor.subscribeCommand(playerProbe.ref, playerId)

      result shouldBe TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a ConnectFour game to GridGameState" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = ConnectFour.empty(alice.id, bob.id)
      ConnectFourModule.serialize(game, None) shouldBe a[GridGameState]
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
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
