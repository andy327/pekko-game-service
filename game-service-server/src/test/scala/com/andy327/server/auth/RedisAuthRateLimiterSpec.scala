package com.andy327.server.auth

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

class RedisAuthRateLimiterSpec extends AnyWordSpec with Matchers with ForAllTestContainer {

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

  private def withLimiter[A](f: (RedisAuthRateLimiter, RedisCommands[IO, String, String]) => IO[A]): A =
    RedisClientResource(redisConfig).use { redis =>
      redis.flushAll *> f(new RedisAuthRateLimiter(redis), redis)
    }.unsafeRunSync()

  "RedisAuthRateLimiter.throttle" should {
    "admit up to the limit, then throttle with a Retry-After bounded by the window" in withLimiter { (limiter, _) =>
      for {
        a <- limiter.throttle("ip", limit = 2, window = 1.minute)
        b <- limiter.throttle("ip", limit = 2, window = 1.minute)
        c <- limiter.throttle("ip", limit = 2, window = 1.minute)
      } yield {
        a shouldBe RateLimitOutcome.Allowed
        b shouldBe RateLimitOutcome.Allowed
        c match {
          case RateLimitOutcome.Limited(retryAfter) => retryAfter should ((be <= 1.minute).and(be > 0.seconds))
          case other                                => fail(s"expected Limited, got $other")
        }
      }
    }

    "give the throttle counter a TTL so it self-expires" in withLimiter { (limiter, redis) =>
      for {
        _ <- limiter.throttle("ip", limit = 1, window = 30.seconds)
        ttl <- redis.ttl("auth-rl:count:ip")
      } yield ttl match {
        case Some(remaining) => remaining should ((be <= 30.seconds).and(be > 0.seconds))
        case None            => fail("expected the throttle counter to carry a TTL")
      }
    }
  }

  "RedisAuthRateLimiter lockout" should {
    "lock only once the failure threshold is reached, and clear on success" in withLimiter { (limiter, _) =>
      val recordFail = limiter.recordFailure("acct", threshold = 3, window = 15.minutes, lockout = 15.minutes)
      for {
        _ <- recordFail
        _ <- recordFail
        beforeThreshold <- limiter.lockStatus("acct")
        _ <- recordFail
        afterThreshold <- limiter.lockStatus("acct")
        _ <- limiter.clearFailures("acct")
        afterClear <- limiter.lockStatus("acct")
      } yield {
        beforeThreshold shouldBe None
        afterThreshold match {
          case Some(remaining) => remaining should ((be <= 15.minutes).and(be > 0.seconds))
          case None            => fail("expected the account to be locked")
        }
        afterClear shouldBe None
      }
    }
  }
}
