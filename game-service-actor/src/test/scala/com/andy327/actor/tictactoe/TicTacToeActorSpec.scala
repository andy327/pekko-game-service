package com.andy327.actor.tictactoe

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._
import scala.jdk.CollectionConverters._
import scala.util.control.NoStackTrace

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.analytics.{AnalyticsPublisher, GameAnalyticsEvent, NoOpAnalyticsPublisher}
import com.andy327.actor.core.{GameManager, PlayerActor, PlayerEvent, TurnBasedGameActor}
import com.andy327.actor.game.{GameState, GridGameState}
import com.andy327.actor.lobby.GameLifecycleStatus
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{Game, GameError, GameId, GameType, PlayerId}
import com.andy327.model.tictactoe.{Location, O, OutOfBounds, TicTacToe, X}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

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
    val (_, behavior) = TicTacToeActor.create(gameId, Seq(alice, bob), persistProbe.ref, gmRef, NoOpAnalyticsPublisher)
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

  /** Nine moves filling the board with no three-in-a-row, ending in a draw (cat's game):
    * {{{ X O X / O O X / X X O }}}
    */
  private val drawMoves: Seq[(PlayerId, Location)] = Seq(
    (alice, Location(0, 0)),
    (bob, Location(1, 1)),
    (alice, Location(0, 2)),
    (bob, Location(0, 1)),
    (alice, Location(2, 0)),
    (bob, Location(1, 0)),
    (alice, Location(1, 2)),
    (bob, Location(2, 2)),
    (alice, Location(2, 1))
  )

  /** Drains the two persistence messages emitted by one applied move: the snapshot save and the history append. */
  private def expectMovePersisted(probe: TestProbe[PersistenceProtocol.Command]): Unit = {
    probe.expectMessageType[PersistenceProtocol.SaveSnapshot]
    probe.expectMessageType[PersistenceProtocol.AppendMove]
    ()
  }

  "TicTacToeActor" should {
    "return an empty 3×3 board on GetState" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(GridGameState(board, current, winner, draw)) = replyProbe.receiveMessage()
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
        NoOpAnalyticsPublisher
      )
      val actor = spawn(behavior)

      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(GridGameState(board, current, winner, draw)) = replyProbe.receiveMessage()
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
          NoOpAnalyticsPublisher
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
        override def playerFor(playerId: PlayerId): Option[Any] = None
        override def players: List[PlayerId] = Nil
        override def moveCount: Int = 0
        override def playerLeft(playerId: PlayerId): Either[GameError, Any] = Left(GameError.Unknown("not implemented"))
      }

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val behavior = TicTacToeActor.fromSnapshot(
        UUID.randomUUID(),
        dummyGame,
        persistProbe.ref,
        dummyGameManager,
        NoOpAnalyticsPublisher
      )
      val actor = spawn(behavior)

      persistProbe.expectTerminated(actor)
    }

    "apply a valid move and switch currentPlayer" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      val Right(GridGameState(board1, current1, _, _)) = replyProbe.receiveMessage()
      board1(0)(0) shouldBe "X"
      current1 shouldBe "O"

      actor ! TurnBasedGameActor.MakeMove(bob, Location(1, 1), replyProbe.ref)
      val Right(GridGameState(board2, current2, _, _)) = replyProbe.receiveMessage()
      board2(0)(0) shouldBe "X"
      board2(1)(1) shouldBe "O"
      current2 shouldBe "X"
    }

    "append each applied move to history with an incrementing seq and the move payload" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      replyProbe.receiveMessage()
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      val first = persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
      first.seq shouldBe 0
      first.playerId shouldBe alice
      first.move shouldBe Json.obj("row" -> 0.asJson, "col" -> 0.asJson)

      actor ! TurnBasedGameActor.MakeMove(bob, Location(1, 1), replyProbe.ref)
      replyProbe.receiveMessage()
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      val second = persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
      second.seq shouldBe 1
      second.playerId shouldBe bob
      second.move shouldBe Json.obj("row" -> 1.asJson, "col" -> 1.asJson)
    }

    "reject a move when it is not the player's turn" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(bob, Location(0, 0), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from a player not in the game" in {
      val eve: PlayerId = UUID.randomUUID()
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(eve, Location(0, 0), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidPlayer(eve))
    }

    "reject an invalid move" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 3), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(OutOfBounds)
    }

    "log success and continue when SnapshotSaved succeeds" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      // Trigger a move, which will cause a SaveSnapshot to be sent
      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      val _ = replyProbe.receiveMessage()

      // Simulate the persistence callback via the actor's message adapter
      val saveMsg = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      saveMsg.replyTo ! PersistenceProtocol.SnapshotSaved(Right(()))

      // Confirm the actor is still functioning
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
      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 0), createTestProbe[Either[GameError, GameState]]().ref)

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.GameStateUpdated]
    }

    "push GameEnded to subscribers when the game completes" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref)
      val subscriberProbe = createTestProbe[PlayerActor.Command]()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        subscriberProbe.expectMessageType[PlayerActor.SendEvent] // GameStateUpdated per move
        expectMovePersisted(persistProbe)
      }

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
    }

    "fan a Broadcast event out to all subscribers" in {
      val (actor, _) = newActor()
      val subscriberProbe = createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      val chat = PlayerEvent.ChatMessage(UUID.randomUUID(), alice, "alice", "gg", Instant.EPOCH)
      actor ! TurnBasedGameActor.Broadcast(chat)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe chat
    }

    "not push events to unsubscribed players" in {
      val (actor, _) = newActor()
      val subscriberProbe = createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe
      actor ! TurnBasedGameActor.Unsubscribe(subscriberProbe.ref)
      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 0), createTestProbe[Either[GameError, GameState]]().ref)

      subscriberProbe.expectNoMessage()
    }

    "drop a terminated subscriber without crashing" in {
      val (actor, _) = newActor()
      val subscriber = spawn(Behaviors.empty[PlayerActor.Command])
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.Subscribe(subscriber, alice)
      // the game actor watches the subscriber; stopping it must drop it via Terminated, not DeathPactException
      testKit.stop(subscriber)

      // the actor is still alive and serving requests
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      replyProbe.receiveMessage() shouldBe a[Right[_, _]]
    }

    "stop after receiving SnapshotSaved(Right) in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
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

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        expectMovePersisted(persistProbe)
      }

      actor ! TurnBasedGameActor.SnapshotSaved(Left(ex))
      persistProbe.expectTerminated(actor)
    }

    "ignore non-SnapshotSaved messages in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        expectMovePersisted(persistProbe)
      }

      // GetState is ignored in terminating — no reply, actor stays alive
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      replyProbe.expectNoMessage()

      // SnapshotSaved then stops the actor
      actor ! TurnBasedGameActor.SnapshotSaved(Right(()))
      persistProbe.expectTerminated(actor)
    }

    "not crash when a watched subscriber terminates while in terminating state" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()
      val subscriber = spawn(Behaviors.empty[PlayerActor.Command])
      val deathWatch = createTestProbe[Any]()

      actor ! TurnBasedGameActor.Subscribe(subscriber, alice)

      // drive the game to completion → actor enters `terminating`, awaiting the final SnapshotSaved
      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage()
        expectMovePersisted(persistProbe)
      }

      // the watched subscriber dies mid-shutdown; without the Terminated handler this is a DeathPactException
      testKit.stop(subscriber)
      // the actor must still be alive (it has not stopped from the subscriber's death)
      intercept[AssertionError](deathWatch.expectTerminated(actor, 150.millis))

      // it stops only once the final snapshot is confirmed
      actor ! TurnBasedGameActor.SnapshotSaved(Right(()))
      persistProbe.expectTerminated(actor)
    }

    "notify the GameManager when a game completes" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val gameId: GameId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref, gameId = gameId)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
        expectMovePersisted(persistProbe)
      }

      gameManagerProbe.receiveMessage() shouldBe GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
    }

    "forfeit the game to the opponent when a player leaves, appending no move but recording the result" in {
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val gameId: GameId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(gmRef = gameManagerProbe.ref, gameId = gameId)
      val subscriberProbe = createTestProbe[PlayerActor.Command]()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      // Bob (O) leaves the in-progress game → Alice (X) wins by forfeit
      actor ! TurnBasedGameActor.PlayerLeft(bob, replyProbe.ref)
      val Right(GridGameState(_, _, winner, _)) = replyProbe.receiveMessage()
      winner shouldBe Some("X")

      // a forfeit saves a final snapshot and records each player's result with the forfeit flag, but — unlike a move —
      // appends nothing to the history log
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(alice, gameId, GameType.TicTacToe, GameResult.Win, forfeit = true)
      )
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(bob, gameId, GameType.TicTacToe, GameResult.Loss, forfeit = true)
      )
      persistProbe.expectNoMessage()

      // the subscriber sees the finished state then the game-ended event; GameManager is notified
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.GameStateUpdated]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
      gameManagerProbe.receiveMessage() shouldBe GameManager.GameCompleted(gameId, GameLifecycleStatus.Completed)
    }

    "record a Win for the winner and a Loss for the loser when the game completes" in {
      val gameId: GameId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(gameId = gameId)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      xWinsMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
        expectMovePersisted(persistProbe)
      }

      // the winning transition records each participant's outcome in seat order: X (alice) won, O (bob) lost
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(alice, gameId, GameType.TicTacToe, GameResult.Win, forfeit = false)
      )
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(bob, gameId, GameType.TicTacToe, GameResult.Loss, forfeit = false)
      )
    }

    "record a Draw for both players when the game ends in a draw" in {
      val gameId: GameId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(gameId = gameId)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      drawMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
        expectMovePersisted(persistProbe)
      }

      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(alice, gameId, GameType.TicTacToe, GameResult.Draw, forfeit = false)
      )
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(bob, gameId, GameType.TicTacToe, GameResult.Draw, forfeit = false)
      )
    }

    "reject a leave from a player not in the game" in {
      val eve: PlayerId = UUID.randomUUID()
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.PlayerLeft(eve, replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidPlayer(eve))
    }

    "emit a GameCompleted analytics event with outcome Draw when the game ends in a draw" in {
      val published = new java.util.concurrent.ConcurrentLinkedQueue[GameAnalyticsEvent]()
      val capturing = new AnalyticsPublisher {
        def publish(event: GameAnalyticsEvent): Unit = { published.add(event); () }
      }
      val gameId: GameId = UUID.randomUUID()
      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val (_, behavior) = TicTacToeActor.create(gameId, Seq(alice, bob), persistProbe.ref, dummyGameManager, capturing)
      val actor = spawn(behavior)
      val replyProbe = createTestProbe[Either[GameError, GameState]]()

      drawMoves.foreach { case (player, loc) =>
        actor ! TurnBasedGameActor.MakeMove(player, loc, replyProbe.ref)
        replyProbe.receiveMessage() shouldBe a[Right[_, _]]
      }

      // the final, non-forfeit transition resolves to the Draw outcome (the `case Draw` branch of applyTransition)
      createTestProbe[Any]().awaitAssert {
        val completed = published.iterator().asScala.collect { case c: GameAnalyticsEvent.GameCompleted => c }.toList
        completed.map(_.outcome) shouldBe List(GameAnalyticsEvent.Outcome.Draw)
        completed.head.gameId shouldBe gameId
        completed.head.moveCount shouldBe 9
      }
    }
  }
}
