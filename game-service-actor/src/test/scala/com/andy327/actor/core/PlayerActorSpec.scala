package com.andy327.actor.core

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.game.GridGameState
import com.andy327.actor.lobby.Player

class PlayerActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "PlayerActor" should {
    "forward SendEvent as a SessionEvent to sessionOut" in {
      val alice = Player("alice")
      val sessionProbe = TestProbe[PlayerActor.SessionOutput]()
      val actor = spawn(PlayerActor(alice, sessionProbe.ref))

      val dummyState = GridGameState(
        board = Vector.fill(3)(Vector.fill(3)("")),
        currentPlayer = "X",
        winner = None,
        draw = false
      )
      val event = PlayerEvent.GameStateUpdated(UUID.randomUUID(), dummyState, spectatorCount = 0)

      actor ! PlayerActor.SendEvent(event)
      sessionProbe.expectMessage(PlayerActor.SessionEvent(event))

      // send a second event to confirm the actor is still alive and processing
      actor ! PlayerActor.SendEvent(event)
      sessionProbe.expectMessageType[PlayerActor.SessionEvent]
    }

    "complete the session stream and stop when it receives Disconnect" in {
      val alice = Player("alice")
      val sessionProbe = TestProbe[PlayerActor.SessionOutput]()
      val actor = spawn(PlayerActor(alice, sessionProbe.ref))
      val probe = TestProbe[PlayerActor.Command]()

      actor ! PlayerActor.Disconnect
      sessionProbe.expectMessage(PlayerActor.SessionComplete)
      probe.expectTerminated(actor)
    }
  }
}
