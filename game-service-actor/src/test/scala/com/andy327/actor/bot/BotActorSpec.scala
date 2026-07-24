package com.andy327.actor.bot

import java.util.UUID

import scala.concurrent.duration._
import scala.util.Random

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{GameActor, GameManager, PlayerActor, PlayerEvent}
import com.andy327.actor.events.NoOpEventPublisher
import com.andy327.actor.game.modules.TicTacToeModule
import com.andy327.actor.game.{GameOperation, GameView, MovePayload}
import com.andy327.actor.lobby.{BotId, GameLifecycleStatus}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.actor.tictactoe.TicTacToeActor
import com.andy327.model.core.{GameError, MatchId, PlayerId, RoomId}
import com.andy327.model.tictactoe.{TicTacToe, X}

class BotActorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  private val botId: PlayerId = BotId.forOrdinal(0)
  private val human: PlayerId = UUID.randomUUID()
  private val roomId: RoomId = UUID.randomUUID()

  /** A bot wired to `gm`. Returns the session ref the game actor would push to — captured from the `register`
    * callback. The think delay defaults to zero so a turn submits synchronously in tests.
    */
  private def newBot(
      gm: ActorRef[GameManager.Command],
      seed: Long = 0,
      thinkDelay: FiniteDuration = Duration.Zero
  ): ActorRef[PlayerActor.Command] = {
    val sessionProbe = TestProbe[ActorRef[PlayerActor.Command]]()
    spawn(BotActor(botId, roomId, RandomPolicy, gm, new Random(seed), thinkDelay)(sessionProbe.ref ! _))
    sessionProbe.receiveMessage()
  }

  /** The bot's view of a TicTacToe game where the bot (X) is to move. */
  private def botToMove: PlayerEvent =
    PlayerEvent.GameStateUpdated(roomId, TicTacToeModule.project(TicTacToe.empty(botId, human), Some(botId)), 0)

  "BotActor" should {
    "submit a legal move when a push shows it is the bot's turn" in {
      val gm = TestProbe[GameManager.Command]()
      val session = newBot(gm.ref)

      session ! PlayerActor.SendEvent(botToMove)

      val op = gm.expectMessageType[GameManager.RunGameOperation]
      op.roomId shouldBe roomId
      op.op match {
        case GameOperation.MakeMove(mover, _: MovePayload.TicTacToeMove) => mover shouldBe botId
        case other                                                       => fail(s"unexpected op: $other")
      }
    }

    "stay silent on a push where it is not the bot's turn" in {
      val gm = TestProbe[GameManager.Command]()
      val session = newBot(gm.ref)

      // the same game projected for the human: X (the bot) is to move, so the human's view offers nothing
      val humanView =
        PlayerEvent.GameStateUpdated(roomId, TicTacToeModule.project(TicTacToe.empty(botId, human), Some(human)), 0)
      session ! PlayerActor.SendEvent(humanView)

      gm.expectNoMessage()
    }

    "submit exactly once per turn, ignoring duplicate pushes during the think delay" in {
      val gm = TestProbe[GameManager.Command]()
      // a real think delay opens the window a duplicate push would arrive in; the pending guard must collapse them
      val session = newBot(gm.ref, thinkDelay = 300.millis)

      session ! PlayerActor.SendEvent(botToMove)
      session ! PlayerActor.SendEvent(botToMove) // redundant re-pushes of the same turn, mid-think
      session ! PlayerActor.SendEvent(botToMove)

      gm.expectMessageType[GameManager.RunGameOperation] // fires once the delay elapses
      gm.expectNoMessage() // the two duplicates raised no further submission
    }

    "act again on the next turn after its move is applied" in {
      val gm = TestProbe[GameManager.Command]()
      val session = newBot(gm.ref)

      session ! PlayerActor.SendEvent(botToMove)
      gm.expectMessageType[GameManager.RunGameOperation]

      // a later board where it is again the bot's turn (bot X to move after O replied)
      val next = TicTacToe.empty(botId, human)
        .play(X, com.andy327.model.tictactoe.Location(0, 0)).toOption.get
        .play(com.andy327.model.tictactoe.O, com.andy327.model.tictactoe.Location(1, 1)).toOption.get
      session ! PlayerActor.SendEvent(
        PlayerEvent.GameStateUpdated(roomId, TicTacToeModule.project(next, Some(botId)), 0)
      )

      gm.expectMessageType[GameManager.RunGameOperation]
    }

    "ignore push events that are not game-state updates" in {
      val gm = TestProbe[GameManager.Command]()
      val session = newBot(gm.ref)

      val chat = PlayerEvent.ChatMessage(roomId, human, "human", "hello bot", java.time.Instant.EPOCH)
      session ! PlayerActor.SendEvent(chat)

      gm.expectNoMessage() // chat is not the bot's concern; it neither acts nor crashes
    }

    "keep playing after a move is reported rejected, and ignore an accepted reply" in {
      val gm = TestProbe[GameManager.Command]()
      val session = newBot(gm.ref)

      session ! PlayerActor.SendEvent(botToMove)
      val op1 = gm.expectMessageType[GameManager.RunGameOperation]
      op1.replyTo ! GameManager.MoveRejected("simulated rejection") // the bot logs and carries on

      session ! PlayerActor.SendEvent(botToMove)
      val op2 = gm.expectMessageType[GameManager.RunGameOperation] // still submits on the next turn
      op2.replyTo ! GameManager.GameStatus(TicTacToeModule.project(TicTacToe.empty(botId, human), None)) // accepted

      session ! PlayerActor.SendEvent(botToMove)
      gm.expectMessageType[GameManager.RunGameOperation] // an accepted reply changes nothing
    }

    "stop when the game ends" in {
      val gm = TestProbe[GameManager.Command]()
      val sessionProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      val bot = spawn(
        BotActor(botId, roomId, RandomPolicy, gm.ref, new Random(0), thinkDelay = Duration.Zero)(sessionProbe.ref ! _)
      )
      val session = sessionProbe.receiveMessage()

      session ! PlayerActor.SendEvent(PlayerEvent.GameEnded(GameLifecycleStatus.Completed))
      createTestProbe().expectTerminated(bot, 3.seconds)
    }

    "stop when its session is disconnected" in {
      val gm = TestProbe[GameManager.Command]()
      val sessionProbe = TestProbe[ActorRef[PlayerActor.Command]]()
      val bot = spawn(
        BotActor(botId, roomId, RandomPolicy, gm.ref, new Random(0), thinkDelay = Duration.Zero)(sessionProbe.ref ! _)
      )
      val session = sessionProbe.receiveMessage()

      session ! PlayerActor.Disconnect
      createTestProbe().expectTerminated(bot, 3.seconds)
    }

    "drive a real game to completion when two bots hold both seats" in {
      // a genuine end-to-end loop: a live game actor pushes views, each bot decides and submits through a relay that
      // routes the move back to the actor exactly as GameManager would, and random TicTacToe always terminates
      val completion = TestProbe[GameManager.Command]()
      val bot0 = BotId.forOrdinal(0)
      val bot1 = BotId.forOrdinal(1)
      val matchId: MatchId = UUID.randomUUID()

      val (_, behavior) = TicTacToeActor.create(
        matchId,
        roomId,
        Seq(bot0, bot1),
        system.ignoreRef[PersistenceProtocol.Command],
        completion.ref,
        NoOpEventPublisher
      )
      val gameActor = spawn(behavior).unsafeUpcast[GameActor.GameCommand]

      // stands in for GameManager's one move-routing responsibility: translate the submitted payload and forward it
      val relay = spawn(Behaviors.receiveMessage[GameManager.Command] {
        case GameManager.RunGameOperation(_, op, _) =>
          TicTacToeModule.toGameCommand(op, system.ignoreRef[Either[GameError, GameView]]).foreach(gameActor ! _)
          Behaviors.same
        case _ => Behaviors.same
      })

      Seq(bot0, bot1).zipWithIndex.foreach { case (id, seed) =>
        spawn(BotActor(id, roomId, RandomPolicy, relay, new Random(seed.toLong), thinkDelay = Duration.Zero) { session =>
          gameActor ! TicTacToeActor.subscribeCommand(session, id)
        })
      }

      // the actor reports the finished match to its GameManager once the bots have played it out
      completion.expectMessageType[GameManager.GameCompleted](5.seconds)
    }
  }
}
