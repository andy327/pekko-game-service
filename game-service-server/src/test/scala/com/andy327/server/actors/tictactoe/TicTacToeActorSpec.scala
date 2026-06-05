package com.andy327.server.actors.tictactoe

import java.util.UUID

import scala.util.control.NoStackTrace

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameId, PlayerId}
import com.andy327.model.tictactoe.{GameError, Location, O, TicTacToe, X}
import com.andy327.server.actors.core.{GameManager, PlayerActor, PlayerEvent}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.{GameState, TicTacToeState}
import com.andy327.server.lobby.GameLifecycleStatus
import com.andy327.server.pubsub.NoOpGameEventPublisher

class TicTacToeActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  /** Spawn a fresh game actor backed by a new persist probe.
    * Optionally override the GameManager ref or supply a specific gameId.
    */
  private def newActor(
      gmRef: ActorRef[GameManager.Command] = dummyGameManager,
      gameId: GameId = UUID.randomUUID()
  ): (ActorRef[TicTacToeActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) = TicTacToeActor.create(gameId, Seq(alice, bob), persistProbe.ref, gmRef, NoOpGameEventPublisher)
    (spawn(behavior), persistProbe)
  }

  /** Five moves in which X wins the top row. */
  private val xWinsMoves: Seq[(PlayerId, Location)] = Seq(
    (alice, Location(0, 0)),
    (bob, Location(1, 0)),
    (alice, Location(0, 1)),
    (bob, Location(1, 1)),
    (alice, Location(0, 2))
  )

  "TicTacToeActor" should {
    "return an empty 3×3 board on GetState" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val Right(TicTacToeState(board, current, winner, draw)) = replyProbe.receiveMessage()
      board.flatten should contain only ""
      current shouldBe "X"
      winner shouldBe None
      draw shouldBe false
    }

    "restore an in-progress game state from snapshot" in {
      val snapshotState = TicTacToe(
        playerX = alice,
        playerO = bob,
        board = Vector(
          Vector(Some(X), None, None),
          Vector(None, Some(O), None),
          Vector(None, None, None)
        ),
        currentPlayer = X,
        winner = None,
        isDraw = false
      )

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val behavior = TicTacToeActor.fromSnapshot(
        UUID.randomUUID(),
        snapshotState,
        persistProbe.ref,
        dummyGameManager,
        NoOpGameEventPublisher
      )
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)

      val Right(TicTacToeState(board, current, winner, draw)) = replyProbe.receiveMessage()
      board(0)(0) shouldBe "X"
      board(1)(1) shouldBe "O"
      current shouldBe "X"
      winner shouldBe None
      draw shouldBe false
    }

    "notify GameManager and stop when restored from a completed snapshot" in {
      val gameId = UUID.randomUUID()
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val completedGame = TicTacToe(
        playerX = alice,
        playerO = bob,
        board = Vector(
          Vector(Some(X), Some(X), Some(X)),
          Vector(Some(O), Some(O), None),
          Vector(None, None, None)
        ),
        currentPlayer = O,
        winner = Some(X),
        isDraw = false
      )

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val actor = spawn(
        TicTacToeActor.fromSnapshot(
          gameId,
          completedGame,
          persistProbe.ref,
          gameManagerProbe.ref,
          NoOpGameEventPublisher
        )
      )

      gameManagerProbe.expectMessage(GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed))
      persistProbe.expectTerminated(actor)
    }

    "fail to restore state from snapshot with an invalid game type" in {
      val dummyGame = new Game[Any, Any, Any, Any, Any] {
        override def play(player: Any, move: Any): Either[Any, Any] = Left("not implemented")
        override def currentState: Any = "dummy"
        override def currentPlayer: Any = "dummy"
        override def gameStatus: Any = "dummy"
      }

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val behavior = TicTacToeActor.fromSnapshot(
        UUID.randomUUID(),
        dummyGame,
        persistProbe.ref,
        dummyGameManager,
        NoOpGameEventPublisher
      )
      val actor = spawn(behavior)

      persistProbe.expectTerminated(actor)
    }

    "apply a valid move and switch currentPlayer" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      val Right(TicTacToeState(board1, current1, _, _)) = replyProbe.receiveMessage()
      board1(0)(0) shouldBe "X"
      current1 shouldBe "O"

      actor ! TicTacToeActor.MakeMove(bob, Location(1, 1), replyProbe.ref)
      val Right(TicTacToeState(board2, current2, _, _)) = replyProbe.receiveMessage()
      board2(0)(0) shouldBe "X"
      board2(1)(1) shouldBe "O"
      current2 shouldBe "X"
    }

    "reject a move when it is not the player's turn" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TicTacToeActor.MakeMove(bob, Location(0, 0), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from a player not in the game" in {
      val eve: PlayerId = UUID.randomUUID()
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TicTacToeActor.MakeMove(eve, Location(0, 0), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidPlayer(eve))
    }

    "reject an invalid move" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TicTacToeActor.MakeMove(alice, Location(0, 3), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.OutOfBounds)
    }

    "log success and continue when SnapshotSaved succeeds" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Trigger a move, which will cause a SaveSnapshot to be sent
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      val _ = replyProbe.receiveMessage()

      // Simulate the persistence callback via the actor's message adapter
      val saveMsg = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      saveMsg.replyTo ! PersistenceProtocol.SnapshotSaved(Right(()))

      // Confirm the actor is still functioning
      actor ! TicTacToeActor.GetState(replyProbe.ref)
      replyProbe.receiveMessage() shouldBe a[Right[_, _]]
    }

    "log error and continue when SnapshotSaved fails" in {
      val (actor, _) = newActor()
      val ex = new RuntimeException("artificial test failure") with NoStackTrace

      actor ! TicTacToeActor.SnapshotSaved(Left(ex))

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TicTacToeActor.GetState(replyProbe.ref)
      replyProbe.receiveMessage() shouldBe a[Right[_, _]]
    }

    "push GameStateUpdated to subscribers after a valid move" in {
      val (actor, _) = newActor()
      val subscriberProbe = createTestProbe[PlayerActor.Command]()

      actor ! TicTacToeActor.Subscribe(subscriberProbe.ref)
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), createTestProbe[Either[GameError, GameState]]().ref)

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.GameStateUpdated]
    }

    "push GameEnded to subscribers when the game completes" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref)
      val subscriberProbe = createTestProbe[PlayerActor.Command]()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TicTacToeActor.Subscribe(subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TicTacToeActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        subscriberProbe.expectMessageType[PlayerActor.SendEvent] // GameStateUpdated per move
        val _ = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      }

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
    }

    "not push events to unsubscribed players" in {
      val (actor, _) = newActor()
      val subscriberProbe = createTestProbe[PlayerActor.Command]()

      actor ! TicTacToeActor.Subscribe(subscriberProbe.ref)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe
      actor ! TicTacToeActor.Unsubscribe(subscriberProbe.ref)
      actor ! TicTacToeActor.MakeMove(alice, Location(0, 0), createTestProbe[Either[GameError, GameState]]().ref)

      subscriberProbe.expectNoMessage()
    }

    "stop after receiving SnapshotSaved(Right) in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TicTacToeActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      }

      actor ! TicTacToeActor.SnapshotSaved(Right(()))
      persistProbe.expectTerminated(actor)
    }

    "stop after receiving SnapshotSaved(Left) in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      val ex = new RuntimeException("snapshot failure") with NoStackTrace

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TicTacToeActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      }

      actor ! TicTacToeActor.SnapshotSaved(Left(ex))
      persistProbe.expectTerminated(actor)
    }

    "ignore non-SnapshotSaved messages in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TicTacToeActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      }

      // GetState is ignored in terminating — no reply, actor stays alive
      actor ! TicTacToeActor.GetState(replyProbe.ref)
      replyProbe.expectNoMessage()

      // SnapshotSaved then stops the actor
      actor ! TicTacToeActor.SnapshotSaved(Right(()))
      persistProbe.expectTerminated(actor)
    }

    "notify the GameManager when a game completes" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val gameId: GameId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref, gameId = gameId)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TicTacToeActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
        val _ = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      }

      gameManagerProbe.receiveMessage() shouldBe GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
    }
  }
}
