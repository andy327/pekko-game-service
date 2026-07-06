package com.andy327.server.auth

import java.time.Instant
import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import dev.profunktor.redis4cats.RedisCommands
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import com.andy327.persistence.db.redis.RedisClientResource

class RedisRevocationStoreSpec extends AnyWordSpec with Matchers with ForAllTestContainer {

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

  private def withStore[A](f: (RedisRevocationStore, RedisCommands[IO, String, String]) => IO[A]): A =
    RedisClientResource(redisConfig).use { redis =>
      redis.flushAll *> f(new RedisRevocationStore(redis), redis)
    }.unsafeRunSync()

  "RedisRevocationStore" should {
    "round-trip a cutoff (to millisecond precision) and report None for an unknown account" in withStore { (store, _) =>
      val id = UUID.randomUUID()
      val cutoff = Instant.ofEpochMilli(1_780_000_000_000L)
      for {
        before <- store.revokedBefore(id)
        _ <- store.revokeBefore(id, cutoff, 1.hour)
        after <- store.revokedBefore(id)
      } yield {
        before shouldBe None
        after shouldBe Some(cutoff)
      }
    }

    "give the cutoff key a TTL so it self-expires" in withStore { (store, redis) =>
      val id = UUID.randomUUID()
      for {
        _ <- store.revokeBefore(id, Instant.now(), 30.seconds)
        ttl <- redis.ttl(s"auth:revoked-before:$id")
      } yield ttl match {
        case Some(remaining) => remaining should ((be <= 30.seconds).and(be > 0.seconds))
        case None            => fail("expected the cutoff key to carry a TTL")
      }
    }
  }
}
