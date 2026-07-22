package com.andy327.actor.battleship

import java.util.UUID

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameManager, PlayerActor, PlayerEvent, TurnBasedGameActor}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.{BattleshipCell, BattleshipState, GameState}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.battleship.{Coord, Fire, Player1, Player2}
import com.andy327.model.core.{GameError, PlayerId}

class BattleshipActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  private def newActor(): (ActorRef[BattleshipActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      BattleshipActor.create(
        UUID.randomUUID(),
        UUID.randomUUID(),
        Seq(alice, bob),
        persistProbe.ref,
        dummyGameManager,
        NoOpEventPublisher
      )
    (spawn(behavior), persistProbe)
  }

  /** Receives the next pushed event and asserts it is a GameStateUpdated carrying a BattleshipState. */
  private def stateFrom(probe: TestProbe[PlayerActor.Command]): BattleshipState =
    probe.expectMessageType[PlayerActor.SendEvent].event match {
      case PlayerEvent.GameStateUpdated(_, s: BattleshipState, _) => s
      case other => fail(s"expected GameStateUpdated(BattleshipState), got $other")
    }

  "BattleshipActor" should {
    "return a fog-of-war view on GetState (no viewer)" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(state: BattleshipState) = replyProbe.receiveMessage()
      state.viewerSeat shouldBe None
      state.board1.flatten should not contain BattleshipCell.Ship
      state.board2.flatten should not contain BattleshipCell.Ship
      state.board1.flatten should contain(BattleshipCell.Unknown)
    }

    "reveal only the subscriber's own fleet on subscribe" in {
      val (actor, _) = newActor()
      val p1 = createTestProbe[PlayerActor.Command]()
      val p2 = createTestProbe[PlayerActor.Command]()
      val spectator = createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(p1.ref, alice)
      val s1 = stateFrom(p1)
      s1.viewerSeat shouldBe Some(Player1)
      s1.board1.flatten should contain(BattleshipCell.Ship) // own fleet revealed
      s1.board2.flatten.toSet shouldBe Set[BattleshipCell](BattleshipCell.Unknown) // opponent fogged, no shots yet

      actor ! TurnBasedGameActor.Subscribe(p2.ref, bob)
      val s2 = stateFrom(p2)
      s2.viewerSeat shouldBe Some(Player2)
      s2.board2.flatten should contain(BattleshipCell.Ship)
      s2.board1.flatten.toSet shouldBe Set[BattleshipCell](BattleshipCell.Unknown)

      actor ! TurnBasedGameActor.Subscribe(spectator.ref, UUID.randomUUID())
      val ss = stateFrom(spectator)
      ss.viewerSeat shouldBe None
      ss.board1.flatten should not contain BattleshipCell.Ship
      ss.board2.flatten should not contain BattleshipCell.Ship
    }

    "push a per-viewer state update to each subscriber after a shot" in {
      val (actor, persistProbe) = newActor()
      val p1 = createTestProbe[PlayerActor.Command]()
      val p2 = createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(p1.ref, alice)
      stateFrom(p1) // initial push
      actor ! TurnBasedGameActor.Subscribe(p2.ref, bob)
      stateFrom(p2) // initial push

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TurnBasedGameActor.MakeMove(alice, Fire(Coord(0, 0)), replyProbe.ref)
      replyProbe.receiveMessage() // mover reply

      val s1 = stateFrom(p1)
      val s2 = stateFrom(p2)

      // P1 fired at P2's waters (board2): cell (0,0) is now resolved for both viewers
      s1.board2(0)(0) should (be(BattleshipCell.Hit).or(be(BattleshipCell.Miss)))
      s2.board2(0)(0) should (be(BattleshipCell.Hit).or(be(BattleshipCell.Miss)))

      // each viewer still sees only their own fleet
      s1.board1.flatten should contain(BattleshipCell.Ship)
      s2.board1.flatten should not contain BattleshipCell.Ship

      // the two viewers receive different projections of the same game
      s1 should not be s2

      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
    }
  }
}
