package com.andy327.actor.connectfour

import java.util.UUID

import scala.util.control.NoStackTrace

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameManager, PlayerActor, PlayerEvent, TurnBasedGameActor}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.{GameState, GridGameState}
import com.andy327.actor.lobby.GameLifecycleStatus
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.connectfour.{ConnectFour, Drop, InvalidColumn, Red, Yellow}
import com.andy327.model.core.{Game, GameError, MatchId, PlayerId}

class ConnectFourActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID() // plays Red
  val bob: PlayerId = UUID.randomUUID() // plays Yellow

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  /** Spawn a fresh game actor backed by a new persist probe. */
  private def newActor(
      gmRef: ActorRef[GameManager.Command] = dummyGameManager,
      matchId: MatchId = UUID.randomUUID()
  ): (ActorRef[ConnectFourActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      ConnectFourActor.create(matchId, matchId, Seq(alice, bob), persistProbe.ref, gmRef, NoOpEventPublisher)
    (spawn(behavior), persistProbe)
  }

  /** Advance `game` through a sequence of (playerId, col) drops.
    *
    * Throws if any move is rejected — only valid test sequences should be passed here.
    */
  private def playMoves(game: ConnectFour, moves: Seq[(PlayerId, Int)]): ConnectFour =
    moves.foldLeft(game) { case (g, (playerId, col)) =>
      val mark = if (playerId == alice) Red else Yellow
      g.play(mark, Drop(col)).getOrElse(throw new IllegalStateException(s"Unexpected invalid move: $playerId col=$col"))
    }

  /** Seven moves in which Red fills column 0 for a vertical win. */
  private val redWinsMoves: Seq[(PlayerId, Int)] = Seq(
    (alice, 0), // Red   row 5 col 0
    (bob, 1), // Yellow row 5 col 1
    (alice, 0), // Red   row 4 col 0
    (bob, 1), // Yellow row 4 col 1
    (alice, 0), // Red   row 3 col 0
    (bob, 1), // Yellow row 3 col 1
    (alice, 0) // Red   row 2 col 0 — four in a row, Red wins
  )

  /** Drains the two persistence messages emitted by one applied move: the snapshot save and the history append. */
  private def expectMovePersisted(probe: TestProbe[PersistenceProtocol.Command]): Unit = {
    probe.expectMessageType[PersistenceProtocol.SaveSnapshot]
    probe.expectMessageType[PersistenceProtocol.AppendMove]
    ()
  }

  "ConnectFourActor" should {
    "return an empty 6×7 board on GetState" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(GridGameState(board, current, winner, draw)) = replyProbe.receiveMessage()
      board should have size 6
      board.head should have size 7
      board.flatten should contain only ""
      current shouldBe "R"
      winner shouldBe None
      draw shouldBe false
    }

    "restore an in-progress game state from snapshot" in {
      val snapshot = playMoves(ConnectFour.empty(alice, bob), Seq((alice, 3)))

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val behavior = ConnectFourActor.fromSnapshot(
        UUID.randomUUID(),
        UUID.randomUUID(),
        snapshot,
        persistProbe.ref,
        dummyGameManager,
        NoOpEventPublisher
      )
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(GridGameState(board, current, winner, draw)) = replyProbe.receiveMessage()
      board(5)(3) shouldBe "R" // Red dropped into col 3 — piece falls to bottom row
      current shouldBe "Y"
      winner shouldBe None
      draw shouldBe false
    }

    "notify GameManager and stop when restored from a completed snapshot" in {
      val matchId = UUID.randomUUID()
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val completedGame = playMoves(ConnectFour.empty(alice, bob), redWinsMoves)

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val actor = spawn(
        ConnectFourActor.fromSnapshot(
          matchId,
          matchId,
          completedGame,
          persistProbe.ref,
          gameManagerProbe.ref,
          NoOpEventPublisher
        )
      )

      gameManagerProbe.expectMessage(GameManager.GameCompleted(matchId, matchId, GameLifecycleStatus.Completed))
      persistProbe.expectTerminated(actor)
    }

    "fail to restore state from snapshot with an invalid game type" in {
      val dummyGame = new Game[Any, Any, Any, Any, Any] {
        override def play(player: Any, move: Any): Either[Any, Any] = Left("not implemented")
        override def currentState: Any = "dummy"
        override def currentPlayer: Any = "dummy"
        override def gameStatus: Any = "dummy"
        override def playerFor(playerId: PlayerId): Option[Any] = None
        override def players: List[PlayerId] = Nil
        override def moveCount: Int = 0
        override def playerLeft(playerId: PlayerId): Either[GameError, Any] = Left(GameError.Unknown("not implemented"))
      }

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val behavior = ConnectFourActor.fromSnapshot(
        UUID.randomUUID(),
        UUID.randomUUID(),
        dummyGame,
        persistProbe.ref,
        dummyGameManager,
        NoOpEventPublisher
      )
      val actor = spawn(behavior)

      persistProbe.expectTerminated(actor)
    }

    "apply a valid move and switch currentPlayer" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Drop(0), replyProbe.ref)
      val Right(GridGameState(board1, current1, _, _)) = replyProbe.receiveMessage()
      board1(5)(0) shouldBe "R" // piece falls to bottom row
      current1 shouldBe "Y"

      actor ! TurnBasedGameActor.MakeMove(bob, Drop(1), replyProbe.ref)
      val Right(GridGameState(board2, current2, _, _)) = replyProbe.receiveMessage()
      board2(5)(0) shouldBe "R"
      board2(5)(1) shouldBe "Y"
      current2 shouldBe "R"
    }

    "append each applied move to history with an incrementing seq and the move payload" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Drop(0), replyProbe.ref)
      replyProbe.receiveMessage()
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      val first = persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
      first.seq shouldBe 0
      first.playerId shouldBe alice
      first.move shouldBe Json.obj("col" -> 0.asJson)

      actor ! TurnBasedGameActor.MakeMove(bob, Drop(1), replyProbe.ref)
      replyProbe.receiveMessage()
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      val second = persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
      second.seq shouldBe 1
      second.playerId shouldBe bob
      second.move shouldBe Json.obj("col" -> 1.asJson)
    }

    "reject a move when it is not the player's turn" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(bob, Drop(0), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from a player not in the game" in {
      val eve: PlayerId = UUID.randomUUID()
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(eve, Drop(0), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidPlayer(eve))
    }

    "reject a move to an out-of-bounds column" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Drop(7), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(InvalidColumn)
    }

    "log success and continue when SnapshotSaved succeeds" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Drop(0), replyProbe.ref)
      val _ = replyProbe.receiveMessage()

      val saveMsg = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      saveMsg.replyTo ! PersistenceProtocol.SnapshotSaved(Right(()))

      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      replyProbe.receiveMessage() shouldBe a[Right[_, _]]
    }

    "log error and continue when SnapshotSaved fails" in {
      val (actor, _) = newActor()
      val ex = new RuntimeException("artificial test failure") with NoStackTrace

      actor ! TurnBasedGameActor.SnapshotSaved(Left(ex))

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      replyProbe.receiveMessage() shouldBe a[Right[_, _]]
    }

    "push GameStateUpdated to subscribers after a valid move" in {
      val (actor, _) = newActor()
      val subscriberProbe = createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      actor ! TurnBasedGameActor.MakeMove(alice, Drop(0), createTestProbe[Either[GameError, GameState]]().ref)

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.GameStateUpdated]
    }

    "push GameEnded to subscribers when the game completes" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref)
      val subscriberProbe = createTestProbe[PlayerActor.Command]()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      redWinsMoves.foreach { case (playerId, col) =>
        actor ! TurnBasedGameActor.MakeMove(playerId, Drop(col), replyProbe.ref)
        replyProbe.receiveMessage()
        subscriberProbe.expectMessageType[PlayerActor.SendEvent] // GameStateUpdated per move
        expectMovePersisted(persistProbe)
      }

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
    }

    "not push events to unsubscribed players" in {
      val (actor, _) = newActor()
      val subscriberProbe = createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe
      actor ! TurnBasedGameActor.Unsubscribe(subscriberProbe.ref)
      actor ! TurnBasedGameActor.MakeMove(alice, Drop(0), createTestProbe[Either[GameError, GameState]]().ref)

      subscriberProbe.expectNoMessage()
    }

    "stop after receiving SnapshotSaved(Right) in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      redWinsMoves.foreach { case (playerId, col) =>
        actor ! TurnBasedGameActor.MakeMove(playerId, Drop(col), replyProbe.ref)
        replyProbe.receiveMessage()
        expectMovePersisted(persistProbe)
      }

      actor ! TurnBasedGameActor.SnapshotSaved(Right(()))
      persistProbe.expectTerminated(actor)
    }

    "stop after receiving SnapshotSaved(Left) in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      val ex = new RuntimeException("snapshot failure") with NoStackTrace

      redWinsMoves.foreach { case (playerId, col) =>
        actor ! TurnBasedGameActor.MakeMove(playerId, Drop(col), replyProbe.ref)
        replyProbe.receiveMessage()
        expectMovePersisted(persistProbe)
      }

      actor ! TurnBasedGameActor.SnapshotSaved(Left(ex))
      persistProbe.expectTerminated(actor)
    }

    "reject a GetState landing in terminating state with GameOver instead of dropping it" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      redWinsMoves.foreach { case (playerId, col) =>
        actor ! TurnBasedGameActor.MakeMove(playerId, Drop(col), replyProbe.ref)
        replyProbe.receiveMessage()
        expectMovePersisted(persistProbe)
      }

      // a request landing in the terminating window gets a clean rejection rather than no reply at all
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      replyProbe.expectMessage(Left(GameError.GameOver))

      // SnapshotSaved then stops the actor
      actor ! TurnBasedGameActor.SnapshotSaved(Right(()))
      persistProbe.expectTerminated(actor)
    }

    "notify the GameManager when a game completes" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val matchId: MatchId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref, matchId = matchId)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      redWinsMoves.foreach { case (playerId, col) =>
        actor ! TurnBasedGameActor.MakeMove(playerId, Drop(col), replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
        expectMovePersisted(persistProbe)
      }

      gameManagerProbe.receiveMessage() shouldBe GameManager.GameCompleted(
        matchId,
        matchId,
        GameLifecycleStatus.Completed
      )
    }
  }
}
