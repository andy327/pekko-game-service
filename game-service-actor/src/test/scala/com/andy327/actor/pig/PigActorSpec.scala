package com.andy327.actor.pig

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameManager, TurnBasedGameActor}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.{GameView, PigView}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.pig.Roll

/** Pig-specific turn-timeout policy: on an expired clock Pig auto-holds rather than forfeits, so an idle seat just
  * passes its turn and the (up to eight player) game keeps going. The shared timer mechanism is covered by
  * `TurnBasedGameActorTimeoutSpec`; here Pig has no clock in the shipped config, so a `TurnTimeout` is sent directly to
  * exercise the policy deterministically.
  */
class PigActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  private val alice: PlayerId = UUID.randomUUID() // seat 0 (P1), moves first
  private val bob: PlayerId = UUID.randomUUID() // seat 1 (P2)

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  private def newActor(): (ActorRef[PigActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      PigActor.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Seq(alice, bob),
        persistProbe.ref,
        dummyGameManager,
        NoOpEventPublisher
      )
    (spawn(behavior), persistProbe)
  }

  private def state(
      actor: ActorRef[PigActor.Command],
      replyProbe: TestProbe[Either[GameError, GameView]]
  ): PigView = {
    actor ! TurnBasedGameActor.GetState(replyProbe.ref)
    replyProbe.receiveMessage().toOption.get.asInstanceOf[PigView]
  }

  "PigActor's turn-timeout policy" should {
    "auto-hold and pass the turn when the clock fires at the start of a turn" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      // Alice is idle from the very start of her turn (turnScore 0); the auto-hold is a no-score pass to Bob
      actor ! TurnBasedGameActor.TurnTimeout(alice, 0)
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessageType[PersistenceProtocol.AppendMove].move.hcursor.get[String]("action") shouldBe
        Right("hold")

      val after = state(actor, replyProbe)
      after.currentPlayer shouldBe 1
      after.scores(0) shouldBe 0
      after.turnScore shouldBe 0
      after.winner shouldBe None
    }

    "auto-hold and bank the accumulated points when the clock fires mid-turn" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      // Alice rolls a 4 (turnScore 4, moveCount 0 → 1), then goes idle
      actor ! TurnBasedGameActor.MakeMove(alice, Roll(4), replyProbe.ref)
      replyProbe.receiveMessage()
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessageType[PersistenceProtocol.AppendMove]

      // the clock for her turn 1 fires: auto-hold banks the 4 and passes to Bob
      actor ! TurnBasedGameActor.TurnTimeout(alice, 1)
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessageType[PersistenceProtocol.AppendMove].move.hcursor.get[String]("action") shouldBe
        Right("hold")

      val after = state(actor, replyProbe)
      after.currentPlayer shouldBe 1
      after.scores(0) shouldBe 4
      after.turnScore shouldBe 0
    }
  }
}
