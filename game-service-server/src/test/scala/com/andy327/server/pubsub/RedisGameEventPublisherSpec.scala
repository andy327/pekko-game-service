package com.andy327.server.pubsub

import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.server.actors.core.PlayerEvent
import com.andy327.server.http.json.TicTacToeState
import com.andy327.server.lobby.GameLifecycleStatus

class RedisGameEventPublisherSpec extends AnyWordSpecLike with Matchers {
  implicit val runtime: IORuntime = IORuntime.global

  private val emptyBoard = Vector.fill(3)(Vector.fill(3)(""))
  private val stateEvent = PlayerEvent.GameStateUpdated(
    TicTacToeState(board = emptyBoard, currentPlayer = "X", winner = None, draw = false)
  )
  private val endedEvent = PlayerEvent.GameEnded(GameLifecycleStatus.Completed)

  "RedisGameEventPublisher" should {
    "publish to the channel named game-events:{gameId}" in {
      val captured = new LinkedBlockingQueue[(String, String)]()
      val doPublish: (String, String) => IO[Unit] = (ch, msg) => IO(captured.put((ch, msg)))
      val publisher = new RedisGameEventPublisher(doPublish)
      val gameId = UUID.randomUUID()

      publisher.publish(gameId, stateEvent)

      val (channel, _) = captured.poll(1, TimeUnit.SECONDS)
      channel shouldBe s"game-events:$gameId"
    }

    "serialize a GameStateUpdated event to JSON with the type discriminator" in {
      val captured = new LinkedBlockingQueue[(String, String)]()
      val doPublish: (String, String) => IO[Unit] = (ch, msg) => IO(captured.put((ch, msg)))
      val publisher = new RedisGameEventPublisher(doPublish)

      publisher.publish(UUID.randomUUID(), stateEvent)

      val (_, json) = captured.poll(1, TimeUnit.SECONDS)
      json should include("GameStateUpdated")
    }

    "serialize a GameEnded event to JSON with the type discriminator" in {
      val captured = new LinkedBlockingQueue[(String, String)]()
      val doPublish: (String, String) => IO[Unit] = (ch, msg) => IO(captured.put((ch, msg)))
      val publisher = new RedisGameEventPublisher(doPublish)

      publisher.publish(UUID.randomUUID(), endedEvent)

      val (_, json) = captured.poll(1, TimeUnit.SECONDS)
      json should include("GameEnded")
    }
  }
}
