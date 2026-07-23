package com.andy327.actor.core

import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.FishingOutcome
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.apache.pekko.actor.typed.ActorRef
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.events.{EventPublisher, GameEvent}
import com.andy327.actor.lobby.{BotId, LobbyMetadata, LobbyRepository}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{GameType, MatchId, PlayerId, RoomId}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository

/** Covers the bot-spawning wiring: a bot seated in a started (or restored) game plays itself, with the think delay
  * pinned to zero so a bot-versus-bot game resolves synchronously.
  */
class GameManagerBotSpec extends AnyWordSpecLike with Matchers with BeforeAndAfterAll {
  private val testKit = ActorTestKit(
    ConfigFactory.parseString("pekko-game-service.bot.think-delay = 0ms").withFallback(ConfigFactory.load())
  )
  import testKit._

  override def afterAll(): Unit = testKit.shutdownTestKit()

  implicit val runtime: IORuntime = IORuntime.global

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(roomId: RoomId): IO[Unit] = IO.unit
    override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
  }

  private val bot0: PlayerId = BotId.forOrdinal(0)
  private val bot1: PlayerId = BotId.forOrdinal(1)

  /** Spawns a GameManager whose analytics events forward to `events`, awaited to the running state before returning. */
  private def newManager(events: TestProbe[GameEvent], repo: GameRepository): ActorRef[GameManager.Command] = {
    val readyProbe = TestProbe[GameManager.Ready.type]()
    val publisher = new EventPublisher {
      override def publish(event: GameEvent): Unit = events.ref ! event
    }
    val gm = spawn(
      GameManager(
        TestProbe[PersistenceProtocol.Command]().ref,
        repo,
        noOpLobbyRepo,
        publisher = publisher,
        onReady = Some(readyProbe.ref)
      )
    )
    readyProbe.expectMessage(GameManager.Ready)
    gm
  }

  /** Waits until a game-completed analytics event arrives, proving a game played itself to a terminal state. */
  private def awaitCompletion(events: TestProbe[GameEvent]): Unit = {
    events.fishForMessage(5.seconds) {
      case _: GameEvent.GameCompleted => FishingOutcome.Complete
      case _                          => FishingOutcome.Continue
    }
    ()
  }

  "GameManager bot wiring" should {
    "let two bots seated in a started game play it to completion" in {
      val events = TestProbe[GameEvent]()
      val gm = newManager(events, new InMemRepo)
      val responseProbe = TestProbe[GameManager.GameResponse]()

      gm ! GameManager.SpawnGame(
        UUID.randomUUID(),
        UUID.randomUUID(),
        GameType.TicTacToe,
        Seq(bot0, bot1),
        responseProbe.ref
      )
      responseProbe.expectMessageType[GameManager.GameStarted]

      awaitCompletion(events) // no human ever moves, yet the game reaches a terminal state
    }

    "re-create bots for an in-progress game restored from a snapshot" in {
      val matchId: MatchId = UUID.randomUUID()
      val restored = new InMemRepo(Map(matchId -> (GameType.TicTacToe, TicTacToe.empty(bot0, bot1))))
      val events = TestProbe[GameEvent]()

      newManager(events, restored) // restore spawns the game actor and re-creates its bots

      awaitCompletion(events) // the re-created bots drive the restored game to completion with no external input
    }
  }
}
