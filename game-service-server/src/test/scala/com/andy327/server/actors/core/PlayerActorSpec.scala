package com.andy327.server.actors.core

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.server.http.json.GridGameState
import com.andy327.server.lobby.Player

class PlayerActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  /** Extracts the text of a WsMessage frame from the probe, failing on any other WsOutput. */
  private def expectText(probe: TestProbe[PlayerActor.WsOutput]): String =
    probe.expectMessageType[PlayerActor.WsMessage].message match {
      case TextMessage.Strict(text) => text
      case other                    => fail(s"Expected a strict text frame, got: $other")
    }

  "PlayerActor" should {
    "forward SendEvent as a TextMessage to wsOut" in {
      val alice = Player("alice")
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val actor = spawn(PlayerActor(alice, wsProbe.ref))

      val dummyState = GridGameState(
        board = Vector.fill(3)(Vector.fill(3)("")),
        currentPlayer = "X",
        winner = None,
        draw = false
      )

      actor ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(dummyState))
      expectText(wsProbe) should include("GameStateUpdated")

      // send a second event to confirm the actor is still alive and processing
      actor ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(dummyState))
      wsProbe.expectMessageType[PlayerActor.WsMessage]
    }

    "complete the WebSocket stream and stop when it receives Disconnect" in {
      val alice = Player("alice")
      val wsProbe = TestProbe[PlayerActor.WsOutput]()
      val actor = spawn(PlayerActor(alice, wsProbe.ref))
      val probe = TestProbe[PlayerActor.Command]()

      actor ! PlayerActor.Disconnect
      wsProbe.expectMessage(PlayerActor.WsComplete)
      probe.expectTerminated(actor)
    }
  }
}
