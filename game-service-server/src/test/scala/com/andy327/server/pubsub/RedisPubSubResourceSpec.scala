package com.andy327.server.pubsub

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import com.andy327.server.analytics.RedisAnalyticsPublisher

/** Integration test for [[RedisPubSubResource]].
  *
  * Spins up a real Redis container to verify end-to-end pub/sub wiring: that messages published via `publishFn` to the
  * analytics channel are received on `subscribeStream`, and that messages on other channels are not.
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
    "deliver a message published to the analytics channel to the subscribe stream" in {
      val payload = """{"type":"GameStarted"}"""

      val messages = RedisPubSubResource(redisConfig).use { case (publishFn, subscribeStream) =>
        for {
          // interruptAfter bounds the wait: if the message is lost (e.g. published before the subscription is
          // established), the stream ends empty and the assertion below fails fast, rather than joinWithNever hanging
          fiber <- subscribeStream.take(1).interruptAfter(5.seconds).compile.toList.start
          _ <- IO.sleep(500.millis) // allow subscription to establish before publishing
          _ <- publishFn(RedisAnalyticsPublisher.Channel, payload)
          msgs <- fiber.joinWithNever
        } yield msgs
      }.unsafeRunSync()

      messages shouldBe List(payload)
    }

    "not deliver messages published to other channels" in {
      val payload = """{"type":"ShouldNotArrive"}"""

      val messages = RedisPubSubResource(redisConfig).use { case (publishFn, subscribeStream) =>
        for {
          // interruptAfter terminates the stream cleanly when the timeout elapses
          fiber <- subscribeStream.take(1).interruptAfter(1.second).compile.toList.start
          _ <- IO.sleep(500.millis)
          _ <- publishFn("unrelated-channel", payload)
          msgs <- fiber.joinWithNever
        } yield msgs
      }.unsafeRunSync()

      messages shouldBe empty
    }
  }
}
