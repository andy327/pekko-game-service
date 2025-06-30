package com.andy327.server.game.modules

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import spray.json._

import com.andy327.model.core.GameError
import com.andy327.model.tictactoe.Location
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.GameState
import com.andy327.server.lobby.Player

class TicTacToeModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "TicTacToeModule" should {
    "successfully parse a valid TicTacToe move JSON" in {
      val validJson = JsObject("row" -> JsNumber(1), "col" -> JsNumber(2))

      val result = TicTacToeModule.parseMove(validJson)
      result shouldBe Right(MovePayload.TicTacToeMove(1, 2))
    }

    "fail to parse an invalid TicTacToe move JSON" in {
      val malformedJson = JsObject("bad" -> JsString("data"))

      val result = TicTacToeModule.parseMove(malformedJson)
      result.isLeft shouldBe true
      result.swap.getOrElse(sys.error("Expected Left but got Right")) should include(
        "Object is missing required member"
      )
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
