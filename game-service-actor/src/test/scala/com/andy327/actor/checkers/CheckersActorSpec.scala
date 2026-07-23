package com.andy327.actor.checkers

import java.util.UUID

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameManager, PlayerActor, PlayerEvent, TurnBasedGameActor}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.{CheckersView, GameView}
import com.andy327.actor.lobby.GameLifecycleStatus
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.checkers.{Black, Checkers, Move, Piece, Red, Square}
import com.andy327.model.core.{GameError, MatchId, PlayerId}

class CheckersActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  val alice: PlayerId = UUID.randomUUID() // plays Red
  val bob: PlayerId = UUID.randomUUID() // plays Black

  private val dummyGameManager: ActorRef[GameManager.Command] = createTestProbe[GameManager.Command]().ref

  /** Spawn a fresh game actor at the standard opening, backed by a new persist probe. */
  private def newActor(
      gmRef: ActorRef[GameManager.Command] = dummyGameManager,
      matchId: MatchId = UUID.randomUUID()
  ): (ActorRef[CheckersActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      CheckersActor.create(matchId, matchId, Seq(alice, bob), persistProbe.ref, gmRef, NoOpEventPublisher)
    (spawn(behavior), persistProbe)
  }

  /** A board holding only the given pieces. */
  private def boardWith(pieces: (Square, Piece)*): Vector[Vector[Option[Piece]]] =
    pieces.foldLeft(Vector.fill(Checkers.Size, Checkers.Size)(Option.empty[Piece])) { case (b, (sq, piece)) =>
      b.updated(sq.row, b(sq.row).updated(sq.col, Some(piece)))
    }

  "CheckersActor" should {
    "return the standard 8×8 opening board on GetState" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(CheckersView(board, current, winner, viewerSeat, _)) = replyProbe.receiveMessage()
      board should have size 8
      board.head should have size 8
      board(5)(0) shouldBe Some(Piece(Red, isKing = false)) // Red pawn in its home rows
      board(2)(1) shouldBe Some(Piece(Black, isKing = false)) // Black pawn in its home rows
      board(3)(4) shouldBe None // empty middle rank
      current shouldBe Red
      winner shouldBe None
      viewerSeat shouldBe None // GetState renders the public view (no specific viewer)
    }

    "apply a valid move and switch currentPlayer" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Move(Square(5, 0), List(Square(4, 1))), replyProbe.ref)

      val Right(CheckersView(board, current, _, viewerSeat, _)) = replyProbe.receiveMessage()
      board(4)(1) shouldBe Some(Piece(Red, isKing = false))
      board(5)(0) shouldBe None
      current shouldBe Black
      viewerSeat shouldBe Some(Red) // the move reply is rendered for the acting player (Red)
    }

    "append an applied move to history as its {from, steps} JSON" in {
      val (actor, persistProbe) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      actor ! TurnBasedGameActor.MakeMove(alice, Move(Square(5, 0), List(Square(4, 1))), replyProbe.ref)
      replyProbe.receiveMessage()

      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      val appended = persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
      appended.seq shouldBe 0
      appended.playerId shouldBe alice
      appended.move shouldBe Json.obj(
        "from" -> Json.obj("row" -> 5.asJson, "col" -> 0.asJson),
        "steps" -> Json.arr(Json.obj("row" -> 4.asJson, "col" -> 1.asJson))
      )
    }

    "reject a move when it is not the player's turn" in {
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      actor ! TurnBasedGameActor.MakeMove(bob, Move(Square(2, 1), List(Square(3, 0))), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidTurn)
    }

    "reject a move from a player not in the game" in {
      val eve: PlayerId = UUID.randomUUID()
      val (actor, _) = newActor()
      val replyProbe = createTestProbe[Either[GameError, GameView]]()

      actor ! TurnBasedGameActor.MakeMove(eve, Move(Square(5, 0), List(Square(4, 1))), replyProbe.ref)
      replyProbe.receiveMessage() shouldBe Left(GameError.InvalidPlayer(eve))
    }

    "restore an in-progress game from snapshot" in {
      val snapshot =
        Checkers(alice, bob, boardWith(Square(5, 2) -> Piece(Red, isKing = false)), Red, None, moveCount = 3)

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val actor = spawn(
        CheckersActor.fromSnapshot(
          UUID.randomUUID(),
          UUID.randomUUID(),
          snapshot,
          persistProbe.ref,
          dummyGameManager,
          NoOpEventPublisher
        )
      )

      val replyProbe = createTestProbe[Either[GameError, GameView]]()
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)

      val Right(CheckersView(board, current, _, _, _)) = replyProbe.receiveMessage()
      board(5)(2) shouldBe Some(Piece(Red, isKing = false))
      current shouldBe Red
    }

    "notify GameManager and stop when restored from a completed snapshot" in {
      val matchId = UUID.randomUUID()
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      val completed =
        Checkers(alice, bob, boardWith(Square(3, 4) -> Piece(Red, isKing = false)), Black, Some(Red), moveCount = 12)

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val actor = spawn(
        CheckersActor.fromSnapshot(
          matchId,
          matchId,
          completed,
          persistProbe.ref,
          gameManagerProbe.ref,
          NoOpEventPublisher
        )
      )

      gameManagerProbe.expectMessage(GameManager.GameCompleted(matchId, matchId, GameLifecycleStatus.Completed))
      persistProbe.expectTerminated(actor)
    }

    "win, notify the GameManager, and push GameEnded when the final capture is played" in {
      val matchId = UUID.randomUUID()
      val gameManagerProbe = createTestProbe[GameManager.Command]()
      // Red to move with a forced jump that captures Black's last piece
      val oneFromWin =
        Checkers(
          alice,
          bob,
          boardWith(Square(5, 2) -> Piece(Red, isKing = false), Square(4, 3) -> Piece(Black, isKing = false)),
          Red,
          None,
          moveCount = 6
        )

      val persistProbe = createTestProbe[PersistenceProtocol.Command]()
      val actor = spawn(
        CheckersActor.fromSnapshot(
          matchId,
          matchId,
          oneFromWin,
          persistProbe.ref,
          gameManagerProbe.ref,
          NoOpEventPublisher
        )
      )

      val subscriberProbe = createTestProbe[PlayerActor.Command]()
      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      val replyProbe = createTestProbe[Either[GameError, GameView]]()
      actor ! TurnBasedGameActor.MakeMove(alice, Move(Square(5, 2), List(Square(3, 4))), replyProbe.ref)

      val Right(CheckersView(_, _, winner, viewerSeat, _)) = replyProbe.receiveMessage()
      winner shouldBe Some(Red)
      viewerSeat shouldBe Some(Red) // rendered for the acting player (Red)

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.GameStateUpdated]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
      // the completion carries the still-subscribed player, so assert the identifying fields rather than exact equality
      val completion = gameManagerProbe.expectMessageType[GameManager.GameCompleted]
      completion.matchId shouldBe matchId
      completion.roomId shouldBe matchId
      completion.result shouldBe GameLifecycleStatus.Completed
    }
  }
}
