package com.andy327.server.game.modules

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.GameError
import com.andy327.model.tictactoe.Location
import com.andy327.server.actors.core.PlayerActor
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.Player

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
        case Right(TicTacToeActor.MakeMove(playerId, loc, reply)) =>
          playerId shouldBe alice.id
          loc shouldBe Location(0, 1)
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = TicTacToeModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TicTacToeActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()

      val result = TicTacToeActor.subscribeCommand(playerProbe.ref)

      result shouldBe TicTacToeActor.Subscribe(playerProbe.ref)
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val unsupportedMove = null.asInstanceOf[MovePayload] // simulate invalid move type

      val result = TicTacToeModule.toGameCommand(
        GameOperation.MakeMove(alice.id, unsupportedMove),
        replyProbe.ref
      )

      result.isLeft shouldBe true
      result.swap.getOrElse(sys.error("Expected Left but got Right")).message should include(
        "Unsupported move type for TicTacToe"
      )
    }
  }
}
