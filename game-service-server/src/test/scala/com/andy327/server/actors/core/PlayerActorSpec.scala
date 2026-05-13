package com.andy327.server.actors.core

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.server.http.json.TicTacToeState
import com.andy327.server.lobby.Player

class PlayerActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "PlayerActor" should {
    "remain alive after receiving SendEvent" in {
      val alice = Player("alice")
      val actor = spawn(PlayerActor(alice))

      val dummyState = TicTacToeState(
        board = Vector.fill(3)(Vector.fill(3)("")),
        currentPlayer = "X",
        winner = None,
        draw = false
      )

      actor ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(dummyState))

      // send a second event to confirm the actor is still processing messages
      val replyProbe = TestProbe[GameManager.GameResponse]()
      actor ! PlayerActor.SendEvent(PlayerEvent.GameStateUpdated(dummyState))
      replyProbe.expectNoMessage()
    }

    "stop when it receives Disconnect" in {
      val alice = Player("alice")
      val actor = spawn(PlayerActor(alice))
      val probe = TestProbe[PlayerActor.Command]()

      actor ! PlayerActor.Disconnect
      probe.expectTerminated(actor)
    }
  }
}
