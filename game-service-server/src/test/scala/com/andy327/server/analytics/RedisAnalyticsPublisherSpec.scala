package com.andy327.server.analytics

import java.util.UUID
import java.util.concurrent.{LinkedBlockingQueue, TimeUnit}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.events.GameEvent
import com.andy327.model.core.GameType

class RedisAnalyticsPublisherSpec extends AnyWordSpecLike with Matchers {
  implicit val runtime: IORuntime = IORuntime.global

  "RedisAnalyticsPublisher" should {
    "publish to the game-analytics channel" in {
      val captured = new LinkedBlockingQueue[(String, String)]()
      val doPublish: (String, String) => IO[Unit] = (ch, msg) => IO(captured.put((ch, msg)))
      val publisher = new RedisAnalyticsPublisher(doPublish)

      publisher.publish(GameEvent.GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2))

      val (channel, _) = captured.poll(1, TimeUnit.SECONDS)
      channel shouldBe RedisAnalyticsPublisher.Channel
    }

    "serialize a GameStarted event to JSON with the type discriminator" in {
      val captured = new LinkedBlockingQueue[(String, String)]()
      val doPublish: (String, String) => IO[Unit] = (ch, msg) => IO(captured.put((ch, msg)))
      val publisher = new RedisAnalyticsPublisher(doPublish)

      publisher.publish(GameEvent.GameStarted(UUID.randomUUID(), GameType.ConnectFour, 2))

      val (_, json) = captured.poll(1, TimeUnit.SECONDS)
      json should include(""""type":"GameStarted"""")
      json should include("ConnectFour")
    }

    "serialize a GameCompleted event to JSON carrying the outcome label" in {
      val captured = new LinkedBlockingQueue[(String, String)]()
      val doPublish: (String, String) => IO[Unit] = (ch, msg) => IO(captured.put((ch, msg)))
      val publisher = new RedisAnalyticsPublisher(doPublish)

      publisher.publish(
        GameEvent.GameCompleted(UUID.randomUUID(), GameType.TicTacToe, GameEvent.Outcome.Forfeit, 3)
      )

      val (_, json) = captured.poll(1, TimeUnit.SECONDS)
      json should include(""""type":"GameCompleted"""")
      json should include(""""outcome":"forfeit"""")
    }
  }
}
