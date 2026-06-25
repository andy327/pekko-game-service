package com.andy327.actor.chat

import java.time.Instant
import java.util.UUID

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.implicits._

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import com.andy327.actor.core.PlayerEvent
import com.andy327.model.core.GameId
import com.andy327.persistence.db.redis.RedisClientResource

class RedisChatRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  private val RedisPort = 6379

  /** Single Redis container spun up once per ScalaTest run. */
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

  /** Runs `f` against a freshly flushed Redis with a chat repo bounded to `maxMessages`. */
  private def withRepo[A](maxMessages: Int = 100)(f: RedisChatRepository => IO[A]): A =
    RedisClientResource(redisConfig).use { redis =>
      redis.flushAll *> f(new RedisChatRepository(redis, maxMessages))
    }.unsafeRunSync()

  private def message(gameId: GameId, text: String): PlayerEvent.ChatMessage =
    PlayerEvent.ChatMessage(gameId, UUID.randomUUID(), "alice", text, Instant.now())

  "RedisChatRepository.recent" should {
    "return appended messages in oldest-first order" in {
      val gameId = UUID.randomUUID()
      withRepo() { repo =>
        for {
          _ <- repo.append(message(gameId, "first"))
          _ <- repo.append(message(gameId, "second"))
          _ <- repo.append(message(gameId, "third"))
          recent <- repo.recent(gameId)
        } yield recent.map(_.text) shouldBe List("first", "second", "third")
      }
    }

    "return an empty list for a game with no messages" in
      withRepo() { repo =>
        repo.recent(UUID.randomUUID()).map(_ shouldBe empty)
      }

    "isolate messages by game" in {
      val gameA = UUID.randomUUID()
      val gameB = UUID.randomUUID()
      withRepo() { repo =>
        for {
          _ <- repo.append(message(gameA, "for-a"))
          _ <- repo.append(message(gameB, "for-b"))
          recentA <- repo.recent(gameA)
        } yield recentA.map(_.text) shouldBe List("for-a")
      }
    }

    "skip corrupt entries rather than failing the whole read" in {
      val gameId = UUID.randomUUID()
      RedisClientResource(redisConfig).use { redis =>
        val repo = new RedisChatRepository(redis)
        redis.flushAll *> (for {
          _ <- repo.append(message(gameId, "good"))
          _ <- redis.lPush(s"chat:$gameId", "{not valid json") // a non-JSON entry slipped into the buffer
          recent <- repo.recent(gameId)
        } yield recent.map(_.text) shouldBe List("good"))
      }.unsafeRunSync()
    }
  }

  "RedisChatRepository.append" should {
    "retain only the most recent messages once the buffer is full" in {
      val gameId = UUID.randomUUID()
      withRepo(maxMessages = 3) { repo =>
        for {
          _ <- List("m1", "m2", "m3", "m4", "m5").traverse_(t => repo.append(message(gameId, t)))
          recent <- repo.recent(gameId)
        } yield recent.map(_.text) shouldBe List("m3", "m4", "m5")
      }
    }
  }
}
