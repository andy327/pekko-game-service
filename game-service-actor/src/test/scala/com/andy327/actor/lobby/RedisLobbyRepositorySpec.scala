package com.andy327.actor.lobby

import java.util.UUID

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import com.andy327.model.core.GameType
import com.andy327.persistence.db.redis.RedisClientResource

class RedisLobbyRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

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

  private def withRepo[A](f: RedisLobbyRepository => IO[A]): A =
    RedisClientResource(redisConfig).use { redis =>
      redis.flushAll *> f(new RedisLobbyRepository(redis))
    }.unsafeRunSync()

  private def newLobby(): LobbyMetadata =
    LobbyMetadata.newLobby(GameType.TicTacToe, Player(UUID.randomUUID(), "alice"))

  "RedisLobbyRepository.saveLobby" should {
    "persist a lobby so it appears in loadAllLobbies" in {
      val lobby = newLobby()
      withRepo { repo =>
        for {
          _ <- repo.saveLobby(lobby)
          lobbies <- repo.loadAllLobbies()
        } yield lobbies.map(_.roomId) should contain(lobby.roomId)
      }
    }

    "overwrite an existing lobby on repeated saves" in {
      val lobby = newLobby()
      val updated = lobby.copy(status = GameLifecycleStatus.ReadyToStart)
      withRepo { repo =>
        for {
          _ <- repo.saveLobby(lobby)
          _ <- repo.saveLobby(updated)
          lobbies <- repo.loadAllLobbies()
        } yield {
          val found = lobbies.find(_.roomId == lobby.roomId)
          found.map(_.status) shouldBe Some(GameLifecycleStatus.ReadyToStart)
        }
      }
    }
  }

  "RedisLobbyRepository.deleteLobby" should {
    "remove the lobby from loadAllLobbies" in {
      val lobby = newLobby()
      withRepo { repo =>
        for {
          _ <- repo.saveLobby(lobby)
          _ <- repo.deleteLobby(lobby.roomId)
          lobbies <- repo.loadAllLobbies()
        } yield lobbies.map(_.roomId) should not contain lobby.roomId
      }
    }
  }

  "RedisLobbyRepository.loadAllLobbies" should {
    "return all saved lobbies" in {
      val lobby1 = newLobby()
      val lobby2 = newLobby()
      withRepo { repo =>
        for {
          _ <- repo.saveLobby(lobby1)
          _ <- repo.saveLobby(lobby2)
          lobbies <- repo.loadAllLobbies()
        } yield (lobbies.map(_.roomId) should contain).allOf(lobby1.roomId, lobby2.roomId)
      }
    }

    "return an empty list when no lobbies have been saved" in
      withRepo { repo =>
        repo.loadAllLobbies().map(_ shouldBe empty)
      }
  }
}
