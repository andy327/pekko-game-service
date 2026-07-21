package com.andy327.actor.core

import java.util.UUID

import com.typesafe.config.ConfigFactory

import io.circe.generic.semiauto.deriveEncoder
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.TurnBasedGameActor.TimeoutAction
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.{GameState, GridGameState}
import com.andy327.actor.lobby.GameLifecycleStatus
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.actor.tictactoe.TicTacToeActor
import com.andy327.model.core.{GameError, GameType, MatchId, PlayerId}
import com.andy327.model.tictactoe.{Location, Mark, O, TicTacToe, X}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

/** Shared per-turn timeout behavior of [[TurnBasedGameActor]], exercised through Tic-Tac-Toe (whose default policy is
  * to forfeit). Tic-Tac-Toe has no turn clock in the shipped config, so on the default test kit no real timer is armed
  * and a `TurnTimeout` can be injected directly for deterministic, timing-free assertions. A separate kit configured
  * with a short clock covers the real arm-and-fire path.
  */
class TurnBasedGameActorTimeoutSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()

  /** A kit whose config gives Tic-Tac-Toe a short turn clock, so a real armed timer fires quickly. */
  private val timedTestKit = ActorTestKit(
    ConfigFactory
      .parseString("pekko-game-service.turn-timeouts.tictactoe = 200ms")
      .withFallback(ConfigFactory.load())
  )

  private val alice: PlayerId = UUID.randomUUID() // seat X, moves first
  private val bob: PlayerId = UUID.randomUUID() // seat O

  /** Spawn a fresh Tic-Tac-Toe actor on `kit` with its own persist probe. */
  private def newActor(
      kit: ActorTestKit,
      gmRef: ActorRef[GameManager.Command],
      matchId: MatchId = UUID.randomUUID()
  ): (ActorRef[TicTacToeActor.Command], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = kit.createTestProbe[PersistenceProtocol.Command]()
    val (_, behavior) =
      TicTacToeActor.create(matchId, matchId, Seq(alice, bob), persistProbe.ref, gmRef, NoOpEventPublisher)
    (kit.spawn(behavior), persistProbe)
  }

  /** A Tic-Tac-Toe actor whose turn-timeout policy auto-plays `move` instead of forfeiting. No shipped game returns
    * `AutoMove` until the per-game policies land, so this test-only binding exercises the shared auto-move path (both
    * its success and its move-rejected guard) independently of any real game's rules.
    */
  private class AutoMovePolicyActor(move: Location)
      extends TurnBasedGameActor[TicTacToe, Location, Mark, GridGameState](
        players => TicTacToe.empty(players(0), players(1)),
        deriveEncoder[Location]
      ) {
    override protected def timeoutAction(game: TicTacToe): TimeoutAction[Location] = TimeoutAction.AutoMove(move)
  }

  /** Spawn an [[AutoMovePolicyActor]] with policy move `move`, on the default kit, with its own persist probe. */
  private def newAutoMoveActor(
      move: Location
  ): (ActorRef[TurnBasedGameActor.Command[Location]], TestProbe[PersistenceProtocol.Command]) = {
    val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
    val gmRef = testKit.createTestProbe[GameManager.Command]().ref
    val matchId = UUID.randomUUID()
    val (_, behavior) =
      new AutoMovePolicyActor(move).create(
        matchId,
        matchId,
        Seq(alice, bob),
        persistProbe.ref,
        gmRef,
        NoOpEventPublisher
      )
    (testKit.spawn(behavior), persistProbe)
  }

  "TurnBasedGameActor turn timeout" should {
    "forfeit the current player when a matching turn timeout fires" in {
      val gameManagerProbe = testKit.createTestProbe[GameManager.Command]()
      val matchId: MatchId = UUID.randomUUID()
      val (actor, persistProbe) = newActor(testKit, gameManagerProbe.ref, matchId)
      val subscriberProbe = testKit.createTestProbe[PlayerActor.Command]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      // it is Alice's turn (X, moveCount 0); her clock expires → default policy forfeits her, so Bob (O) wins
      actor ! TurnBasedGameActor.TurnTimeout(alice, 0)

      // a timeout forfeit behaves exactly like a PlayerLeft: final snapshot, per-player results in seat order, no
      // history append
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(alice, matchId, GameType.TicTacToe, GameResult.Loss, forfeit = true)
      )
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(bob, matchId, GameType.TicTacToe, GameResult.Win, forfeit = true)
      )
      persistProbe.expectNoMessage()

      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe a[PlayerEvent.GameStateUpdated]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent].event shouldBe
        PlayerEvent.GameEnded(GameLifecycleStatus.Completed)
      gameManagerProbe.receiveMessage() shouldBe a[GameManager.GameCompleted]
    }

    "ignore a turn timeout from a turn that has already advanced (stale-timer race)" in {
      val gameManagerProbe = testKit.createTestProbe[GameManager.Command]()
      val (actor, persistProbe) = newActor(testKit, gameManagerProbe.ref)
      val subscriberProbe = testKit.createTestProbe[PlayerActor.Command]()
      val replyProbe = testKit.createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.Subscribe(subscriberProbe.ref, alice)
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // initial state push on subscribe

      // Alice actually moves before her clock would fire: moveCount advances 0 → 1 and it becomes Bob's turn
      actor ! TurnBasedGameActor.MakeMove(alice, Location(0, 0), replyProbe.ref)
      replyProbe.expectMessageType[Right[GameError, GameState]]
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessageType[PersistenceProtocol.AppendMove]
      subscriberProbe.expectMessageType[PlayerActor.SendEvent] // GameStateUpdated for the move

      // the stale timer for Alice's turn 0 now fires late — it must be ignored, not forfeit Alice or anyone
      actor ! TurnBasedGameActor.TurnTimeout(alice, 0)

      persistProbe.expectNoMessage() // no snapshot, no results — nothing happened
      subscriberProbe.expectNoMessage() // no state update, no GameEnded
      gameManagerProbe.expectNoMessage()

      // the game is still in progress with Bob to move
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      val Right(GridGameState(_, current, winner, _, _)) = replyProbe.receiveMessage()
      current shouldBe O
      winner shouldBe None
    }

    "ignore a turn timeout naming a player who is not the current player" in {
      val gameManagerProbe = testKit.createTestProbe[GameManager.Command]()
      val (actor, persistProbe) = newActor(testKit, gameManagerProbe.ref)

      // moveCount 0 matches, but it is Alice's turn, not Bob's — the guard rejects the mismatched player
      actor ! TurnBasedGameActor.TurnTimeout(bob, 0)

      persistProbe.expectNoMessage()
      gameManagerProbe.expectNoMessage()
    }

    "apply the auto-move and record it when the policy returns AutoMove" in {
      // policy auto-plays the empty top-left cell; Alice (X) is to move at turn 0
      val (actor, persistProbe) = newAutoMoveActor(Location(0, 0))
      val replyProbe = testKit.createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.TurnTimeout(alice, 0)

      // an auto-move is a real move: it saves a snapshot and appends to the history log (unlike a forfeit)
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessageType[PersistenceProtocol.AppendMove]

      // the board advanced: X is placed and it is now O's turn
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      val Right(GridGameState(board, current, winner, _, _)) = replyProbe.receiveMessage()
      board(0)(0) shouldBe Some(X)
      current shouldBe O
      winner shouldBe None
    }

    "leave the game untouched when the policy's AutoMove is rejected by the model" in {
      // policy auto-plays an out-of-bounds cell, which TicTacToe.play rejects → the guard logs and no-ops
      val (actor, persistProbe) = newAutoMoveActor(Location(9, 9))
      val replyProbe = testKit.createTestProbe[Either[GameError, GameState]]()

      actor ! TurnBasedGameActor.TurnTimeout(alice, 0)

      persistProbe.expectNoMessage() // no snapshot, no append — the rejected move changed nothing

      // still a fresh game with Alice (X) to move
      actor ! TurnBasedGameActor.GetState(replyProbe.ref)
      val Right(GridGameState(board, current, winner, _, _)) = replyProbe.receiveMessage()
      board.flatten should contain only None
      current shouldBe X
      winner shouldBe None
    }

    "arm a turn clock and forfeit the idle current player once it expires" in {
      val gameManagerProbe = timedTestKit.createTestProbe[GameManager.Command]()
      val matchId: MatchId = UUID.randomUUID()
      val (_, persistProbe) = newActor(timedTestKit, gameManagerProbe.ref, matchId)

      // no one moves; the armed 200ms clock fires on its own and forfeits the idle current player (Alice), so Bob wins
      persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(alice, matchId, GameType.TicTacToe, GameResult.Loss, forfeit = true)
      )
      persistProbe.expectMessage(
        PersistenceProtocol.RecordGameResult(bob, matchId, GameType.TicTacToe, GameResult.Win, forfeit = true)
      )
    }
  }
}
