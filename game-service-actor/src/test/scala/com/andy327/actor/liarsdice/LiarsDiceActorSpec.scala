package com.andy327.actor.liarsdice

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameManager, TurnBasedGameActor}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.GameView
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{GameError, PlayerId}
import com.andy327.model.liarsdice.{Bid, Challenge, MakeBid}

/** Drives the shared turn-based actor with Liar's Dice moves to lock in the move-log encoder — in particular that a
  * challenge never records its server-rolled dice pool (the history endpoint is public) and that a wild "ones" bid
  * records a null face. State projection and rules are covered by `LiarsDiceViewSpec` and `LiarsDiceSpec`.
  */
class LiarsDiceActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  private def newActor(): (ActorRef[LiarsDiceActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      LiarsDiceActor.create(
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
      actor: ActorRef[LiarsDiceActor.Command],
      persistProbe: TestProbe[PersistenceProtocol.Command],
      player: PlayerId,
      move: com.andy327.model.liarsdice.LiarsDiceMove
  ): io.circe.Json = {
    val replyProbe = createTestProbe[Either[GameError, GameView]]()
    actor ! TurnBasedGameActor.MakeMove(player, move, replyProbe.ref)
    replyProbe.receiveMessage()
    persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
    persistProbe.expectMessageType[PersistenceProtocol.AppendMove].move
  }

  "LiarsDiceActor's move-log encoder" should {
    "record a numbered bid's quantity and face" in {
      val (actor, persistProbe) = newActor()
      val json = loggedMove(actor, persistProbe, alice, MakeBid(Bid(2, Some(4))))
      json.hcursor.get[String]("action") shouldBe Right("bid")
      json.hcursor.get[Int]("quantity") shouldBe Right(2)
      json.hcursor.get[Int]("face") shouldBe Right(4)
    }

    "record a wild ones bid with a null face" in {
      val (actor, persistProbe) = newActor()
      loggedMove(actor, persistProbe, alice, MakeBid(Bid(2, Some(4))))
      // "2 ones" is the next clockwise ones space after "2 fours", so this is a legal raise
      val json = loggedMove(actor, persistProbe, bob, MakeBid(Bid(2, None)))
      json.hcursor.get[Int]("quantity") shouldBe Right(2)
      json.hcursor.downField("face").focus.flatMap(_.asNull) shouldBe Some(())
    }

    "record only the action for a challenge, never the dice pool" in {
      val (actor, persistProbe) = newActor()
      loggedMove(actor, persistProbe, alice, MakeBid(Bid(2, Some(4))))
      val json = loggedMove(actor, persistProbe, bob, Challenge(List.fill(30)(3)))
      json.hcursor.get[String]("action") shouldBe Right("challenge")
      json.asObject.map(_.keys.toList) shouldBe Some(List("action")) // no dice/pool leaked to the public history log
    }
  }
}
