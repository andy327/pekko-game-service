package com.andy327.server.actors.core

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.server.lobby.Player

class PlayerManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "PlayerManager" should {
    "register a player and return its actor ref" in {
      val pm = spawn(PlayerManager())
      val replyProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      val alice = Player("alice")

      pm ! PlayerManager.RegisterPlayer(alice, replyProbe.ref)

      val ref = replyProbe.expectMessageType[ActorRef[PlayerActor.Command]]
      ref should not be null
    }

    "return Some(ref) for a registered player" in {
      val pm = spawn(PlayerManager())
      val registerProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      val lookupProbe = TestProbe[Option[ActorRef[PlayerActor.Command]]]()
      val alice = Player("alice")

      pm ! PlayerManager.RegisterPlayer(alice, registerProbe.ref)
      val ref = registerProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      pm ! PlayerManager.LookupPlayer(alice.id, lookupProbe.ref)
      lookupProbe.expectMessage(Some(ref))
    }

    "return None for an unknown player" in {
      val pm = spawn(PlayerManager())
      val lookupProbe = TestProbe[Option[ActorRef[PlayerActor.Command]]]()

      pm ! PlayerManager.LookupPlayer(UUID.randomUUID(), lookupProbe.ref)
      lookupProbe.expectMessage(None)
    }

    "stop the old actor and return a new ref on reconnect" in {
      val pm = spawn(PlayerManager())
      val replyProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      val alice = Player("alice")

      pm ! PlayerManager.RegisterPlayer(alice, replyProbe.ref)
      val firstRef = replyProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      pm ! PlayerManager.RegisterPlayer(alice, replyProbe.ref)
      val secondRef = replyProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      secondRef should not be theSameInstanceAs(firstRef)
      replyProbe.expectTerminated(firstRef)
    }

    "remove a player on PlayerDisconnected" in {
      val pm = spawn(PlayerManager())
      val registerProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      val lookupProbe = TestProbe[Option[ActorRef[PlayerActor.Command]]]()
      val alice = Player("alice")

      pm ! PlayerManager.RegisterPlayer(alice, registerProbe.ref)
      registerProbe.expectMessageType[ActorRef[PlayerActor.Command]]

      pm ! PlayerManager.PlayerDisconnected(alice.id)

      pm ! PlayerManager.LookupPlayer(alice.id, lookupProbe.ref)
      lookupProbe.expectMessage(None)
    }
  }
}
