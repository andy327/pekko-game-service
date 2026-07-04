package com.andy327.actor.texasholdem

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameManager, TurnBasedGameActor}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.GameState
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.holdem.Action.{Call, Raise}
import com.andy327.model.holdem.{Card, HoldEmMove}

/** Drives the shared turn-based actor with Texas Hold 'Em moves to lock in the move-log encoder — in particular that
  * the deck each move carries never reaches the public history log, and that a raise records its amount. State
  * projection and rules are covered by `TexasHoldEmStateSpec` and `TexasHoldEmSpec`.
  */
class TexasHoldEmActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  private val deck: List[Card] = Card.deck // any full deck; the encoder must never log it

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  private def newActor(): (ActorRef[TexasHoldEmActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      TexasHoldEmActor.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Seq(alice, bob),
        persistProbe.ref,
        dummyGameManager,
        NoOpEventPublisher
      )
    (spawn(behavior), persistProbe)
  }

  /** Applies a move and returns the JSON the actor appended to the move-log for it. */
  private def loggedMove(
      actor: ActorRef[TexasHoldEmActor.Command],
      persistProbe: TestProbe[PersistenceProtocol.Command],
      player: PlayerId,
      move: HoldEmMove
  ): io.circe.Json = {
    val replyProbe = createTestProbe[Either[GameError, GameState]]()
    actor ! TurnBasedGameActor.MakeMove(player, move, replyProbe.ref)
    replyProbe.receiveMessage()
    persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
    persistProbe.expectMessageType[PersistenceProtocol.AppendMove].move
  }

  "TexasHoldEmActor's move-log encoder" should {
    "record a raise's amount but never the deck it carries" in {
      val (actor, persistProbe) = newActor()
      // heads-up, seat 0 (button/SB) is first to act pre-flop and raises to 40
      val json = loggedMove(actor, persistProbe, alice, HoldEmMove(Raise(40), deck))
      json.hcursor.get[String]("action") shouldBe Right("raise")
      json.hcursor.get[Int]("amount") shouldBe Right(40)
      json.asObject.map(_.keys.toList) shouldBe Some(List("action", "amount")) // no deck leaked to the history log
    }

    "record only the action for a call, never the deck" in {
      val (actor, persistProbe) = newActor()
      val json = loggedMove(actor, persistProbe, alice, HoldEmMove(Call, deck))
      json.hcursor.get[String]("action") shouldBe Right("call")
      json.asObject.map(_.keys.toList) shouldBe Some(List("action"))
    }
  }
}
