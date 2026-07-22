package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{GameOperation, GameState, GridGameState, MovePayload}
import com.andy327.actor.lobby.Player
import com.andy327.actor.tictactoe.TicTacToeActor
import com.andy327.model.core.GameError
import com.andy327.model.tictactoe.{Location, TicTacToe}

class TicTacToeModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "TicTacToeModule" should {
    "successfully decode a valid TicTacToe move JSON" in {
      val json = """{"row":1,"col":2}"""
      decode[MovePayload](json)(TicTacToeModule.moveDecoder) shouldBe Right(MovePayload.TicTacToeMove(1, 2))
    }

    "fail to decode an invalid TicTacToe move JSON" in {
      val json = """{"bad":"data"}"""
      decode[MovePayload](json)(TicTacToeModule.moveDecoder).isLeft shouldBe true
    }

    "convert a valid GameOperation.MakeMove to a GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val move = MovePayload.TicTacToeMove(0, 1)

      val result = TicTacToeModule.toGameCommand(GameOperation.MakeMove(alice.id, move), replyProbe.ref)

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, loc, reply)) =>
          playerId shouldBe alice.id
          loc shouldBe Location(0, 1)
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = TicTacToeModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()

      val result = TicTacToeActor.subscribeCommand(playerProbe.ref, playerId)

      result shouldBe TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "offer the player to act their own moves, and nobody else any" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = TicTacToe.empty(alice.id, bob.id) // X (alice) leads

      val toAct = TicTacToeModule.serialize(game, Some(alice.id)).asInstanceOf[GridGameState]
      toAct.legalMoves should have size 9

      TicTacToeModule.serialize(game, Some(bob.id)).asInstanceOf[GridGameState].legalMoves shouldBe empty
      TicTacToeModule.serialize(game, None).asInstanceOf[GridGameState].legalMoves shouldBe empty // spectator
    }

    "serialize a TicTacToe game to GridGameState" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val game = TicTacToe.empty(alice.id, bob.id)
      TicTacToeModule.serialize(game, None) shouldBe a[GridGameState]
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val unsupportedMove = null.asInstanceOf[MovePayload] // simulate invalid move type

      val result = TicTacToeModule.toGameCommand(
        GameOperation.MakeMove(alice.id, unsupportedMove),
        replyProbe.ref
      )

      val Left(err) = result
      err.message should include("Unsupported move type for TicTacToe")
    }
  }
}
