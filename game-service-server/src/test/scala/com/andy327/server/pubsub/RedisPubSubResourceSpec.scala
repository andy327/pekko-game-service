package com.andy327.server.pubsub

import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

/** Integration test for [[RedisPubSubResource]].
  *
  * Spins up a real Redis container to verify end-to-end pub/sub wiring: that messages published via `publishFn` are
  * received on `subscribeStream`, and that the `game-events:*` pattern filter works correctly.
  */
class RedisPubSubResourceSpec extends AnyWordSpec with Matchers with ForAllTestContainer {
  private val RedisPort = 6379

  override val container: GenericContainer = GenericContainer(
    dockerImage = "redis:7-alpine",
    exposedPorts = Seq(RedisPort),
    waitStrategy = Wait.forListeningPort()
  )

  private def redisConfig = ConfigFactory.parseString(s"""
    pekko-game-service.redis {
      uri = "redis://${container.containerIpAddress}:${container.mappedPort(RedisPort)}"
    }
  """)

  "RedisPubSubResource" should {
    "deliver a published message to the subscribe stream on a matching channel" in {
      val gameId = UUID.randomUUID()
      val channel = s"game-events:$gameId"
      val payload = """{"type":"GameStateUpdated"}"""

      val messages = RedisPubSubResource(redisConfig).use { case (publishFn, subscribeStream) =>
        for {
          fiber <- subscribeStream.take(1).compile.toList.start
          _ <- IO.sleep(500.millis) // allow subscription to establish before publishing
          _ <- publishFn(channel, payload)
          msgs <- fiber.joinWithNever
        } yield msgs
      }.unsafeRunSync()

      messages should have size 1
      val (ch, data) = messages.head
      ch shouldBe channel
      data shouldBe payload
    }

    "not deliver messages published to channels outside the game-events:* pattern" in {
      val payload = """{"type":"ShouldNotArrive"}"""

      val messages = RedisPubSubResource(redisConfig).use { case (publishFn, subscribeStream) =>
        for {
          // interruptAfter terminates the stream cleanly when the timeout elapses
          fiber <- subscribeStream.take(1).interruptAfter(1.second).compile.toList.start
          _ <- IO.sleep(500.millis)
          _ <- publishFn("unrelated-channel:ignored", payload)
          msgs <- fiber.joinWithNever
        } yield msgs
      }.unsafeRunSync()

      messages shouldBe empty
    }
  }
}
