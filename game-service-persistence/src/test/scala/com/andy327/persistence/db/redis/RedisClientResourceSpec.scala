package com.andy327.persistence.db.redis

import com.typesafe.config.ConfigFactory

import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

class RedisClientResourceSpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  private val RedisPort = 6379

  /** Single Redis container spun up once per ScalaTest run. */
  override val container: GenericContainer = GenericContainer(
    dockerImage = "redis:7-alpine",
    exposedPorts = Seq(RedisPort),
    waitStrategy = Wait.forListeningPort()
  )

  "RedisClientResource" should {
    "perform a SET/GET round-trip against a live Redis instance" in {
      val config = ConfigFactory.parseString(s"""
        pekko-game-service.redis {
          uri = "redis://${container.containerIpAddress}:${container.mappedPort(RedisPort)}"
        }
      """)

      RedisClientResource(config).use { redis =>
        for {
          _ <- redis.set("ping", "pong")
          got <- redis.get("ping")
        } yield got shouldBe Some("pong")
      }.unsafeRunSync()
    }
  }
}
