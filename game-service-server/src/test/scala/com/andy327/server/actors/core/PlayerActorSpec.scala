package com.andy327.server.actors.core

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.server.http.json.TicTacToeState
import com.andy327.server.lobby.Player

class PlayerActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "PlayerActor" should {
    "forward SendEvent as a TextMessage to wsOut" in {
      val alice = Player("alice")
      val wsProbe = TestProbe[Message]()
      val actor = spawn(PlayerActor(alice, wsProbe.ref))

      val dummyState = TicTacToeState(
        board = Vector.fill(3)(Vector.fill(3)("")),
        currentPlayer = "X",
        winner = None,
        draw = false
      )

      actor ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(dummyState))
      val msg1 = wsProbe.expectMessageType[TextMessage.Strict]
      msg1.text should include("GameStateUpdated")

      // send a second event to confirm the actor is still alive and processing
      actor ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(dummyState))
      wsProbe.expectMessageType[TextMessage.Strict]
    }

    "forward SendRawJson as a TextMessage directly to wsOut" in {
      val alice = Player("alice")
      val wsProbe = TestProbe[Message]()
      val actor = spawn(PlayerActor(alice, wsProbe.ref))
      val rawJson = """{"type":"GameStateUpdated","board":[]}"""

      actor ! PlayerActor.SendRawJson(rawJson)
      val msg = wsProbe.expectMessageType[TextMessage.Strict]
      msg.text shouldBe rawJson
    }

    "stop when it receives Disconnect" in {
      val alice = Player("alice")
      val wsProbe = TestProbe[Message]()
      val actor = spawn(PlayerActor(alice, wsProbe.ref))
      val probe = TestProbe[PlayerActor.Command]()

      actor ! PlayerActor.Disconnect
      probe.expectTerminated(actor)
    }
  }
}
