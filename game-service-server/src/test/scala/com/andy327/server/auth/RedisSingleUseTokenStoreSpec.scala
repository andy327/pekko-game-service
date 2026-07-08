package com.andy327.server.auth

import java.util.UUID

import scala.concurrent.duration._

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import cats.syntax.parallel._

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import dev.profunktor.redis4cats.RedisCommands
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import com.andy327.persistence.db.redis.RedisClientResource

class RedisSingleUseTokenStoreSpec extends AnyWordSpec with Matchers with ForAllTestContainer {

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

  private def withStore[A](f: (RedisSingleUseTokenStore, RedisCommands[IO, String, String]) => IO[A]): A =
    RedisClientResource(redisConfig).use { redis =>
      redis.flushAll *> f(new RedisSingleUseTokenStore(redis), redis)
    }.unsafeRunSync()

  "RedisSingleUseTokenStore" should {
    "redeem a freshly issued token for its account exactly once" in withStore { (store, _) =>
      val account = UUID.randomUUID()
      for {
        token <- store.issue(TokenPurpose.PasswordReset, account, 1.hour)
        first <- store.consume(TokenPurpose.PasswordReset, token)
        second <- store.consume(TokenPurpose.PasswordReset, token)
      } yield {
        first shouldBe Some(account)
        second shouldBe None
      }
    }

    "redeem a token exactly once even when many requests race to redeem it" in withStore { (store, _) =>
      val account = UUID.randomUUID()
      for {
        token <- store.issue(TokenPurpose.PasswordReset, account, 1.hour)
        results <- List.fill(32)(()).parTraverse(_ => store.consume(TokenPurpose.PasswordReset, token))
      } yield results.count(_.contains(account)) shouldBe 1
    }

    "reject an unknown token and one presented under the wrong purpose" in withStore { (store, _) =>
      val account = UUID.randomUUID()
      for {
        token <- store.issue(TokenPurpose.PasswordReset, account, 1.hour)
        unknown <- store.consume(TokenPurpose.PasswordReset, "not-a-real-token")
        wrongPurpose <- store.consume(TokenPurpose.EmailVerification, token)
        rightPurpose <- store.consume(TokenPurpose.PasswordReset, token)
      } yield {
        unknown shouldBe None
        wrongPurpose shouldBe None
        rightPurpose shouldBe Some(account)
      }
    }

    "give the token key a TTL so it self-expires" in withStore { (store, redis) =>
      val account = UUID.randomUUID()
      for {
        token <- store.issue(TokenPurpose.PasswordReset, account, 30.seconds)
        key = s"auth:token:${SingleUseTokenStore.storeKey(TokenPurpose.PasswordReset, token)}"
        ttl <- redis.ttl(key)
      } yield ttl match {
        case Some(remaining) => remaining should ((be <= 30.seconds).and(be > 0.seconds))
        case None            => fail("expected the token key to carry a TTL")
      }
    }
  }
}
