package com.andy327.persistence.db.redis

import java.util.UUID

import com.typesafe.config.ConfigFactory

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, GenericContainer}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import org.testcontainers.containers.wait.strategy.Wait

import com.andy327.model.core.{Game, GameId, GameType}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository
import com.andy327.persistence.db.schema.GameTypeCodecs

class RedisGameRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

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

  /** Acquires a RedisGameRepository and the underlying RedisCommands for the duration of `f`. */
  private def withResources[A](stub: GameRepository)(
      f: (RedisGameRepository, dev.profunktor.redis4cats.RedisCommands[IO, String, String]) => IO[A]
  ): A =
    RedisClientResource(redisConfig).use { redis =>
      f(new RedisGameRepository(stub, redis), redis)
    }.unsafeRunSync()

  private def newGame(): (GameId, TicTacToe) =
    UUID.randomUUID() -> TicTacToe.empty(UUID.randomUUID(), UUID.randomUUID())

  // Stub GameRepository backed by an in-memory map with call counters
  private class StubGameRepository(
      private var games: Map[GameId, (GameType, Game[_, _, _, _, _])] = Map.empty
  ) extends GameRepository {
    var initializeCallCount: Int = 0
    var loadGameCallCount: Int = 0
    var saveGameCallCount: Int = 0

    override def initialize(): IO[Unit] = IO(initializeCallCount += 1)

    override def saveGame(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] =
      IO { saveGameCallCount += 1; games = games + (gameId -> (gameType, game)) }

    override def loadGame(gameId: GameId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] =
      IO { loadGameCallCount += 1; games.get(gameId).map(_._2) }

    override def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] =
      IO.pure(games)
  }

  "RedisGameRepository.initialize" should {
    "delegate to the underlying game repository" in {
      val stub = new StubGameRepository()
      withResources(stub) { (repo, _) =>
        repo.initialize().map(_ => stub.initializeCallCount shouldBe 1)
      }
    }
  }

  "RedisGameRepository.saveGame" should {
    "write the serialized game state to Redis and delegate to Postgres" in {
      val (gameId, game) = newGame()
      val stub = new StubGameRepository()

      withResources(stub) { (repo, redis) =>
        for {
          _ <- repo.saveGame(gameId, GameType.TicTacToe, game)
          cached <- redis.get(s"game:$gameId")
        } yield {
          cached shouldBe Some(GameTypeCodecs.serializeGame(GameType.TicTacToe, game))
          stub.saveGameCallCount shouldBe 1
        }
      }
    }
  }

  "RedisGameRepository.loadGame" should {
    "return the cached value without calling Postgres on a cache hit" in {
      val (gameId, game) = newGame()
      val stub = new StubGameRepository() // loadGame would return None

      withResources(stub) { (repo, redis) =>
        for {
          _ <- redis.set(s"game:$gameId", GameTypeCodecs.serializeGame(GameType.TicTacToe, game))
          result <- repo.loadGame(gameId, GameType.TicTacToe)
        } yield {
          result shouldBe Some(game)
          stub.loadGameCallCount shouldBe 0
        }
      }
    }

    "fall back to Postgres and warm the cache on a cache miss" in {
      val (gameId, game) = newGame()
      val stub = new StubGameRepository(Map(gameId -> (GameType.TicTacToe, game)))

      withResources(stub) { (repo, redis) =>
        for {
          result <- repo.loadGame(gameId, GameType.TicTacToe)
          cached <- redis.get(s"game:$gameId")
        } yield {
          result shouldBe Some(game)
          stub.loadGameCallCount shouldBe 1
          cached shouldBe Some(GameTypeCodecs.serializeGame(GameType.TicTacToe, game))
        }
      }
    }

    "return None and leave the cache empty when the game is not found in Postgres" in {
      val gameId = UUID.randomUUID()
      val stub = new StubGameRepository() // no games — loadGame returns None

      withResources(stub) { (repo, redis) =>
        for {
          result <- repo.loadGame(gameId, GameType.TicTacToe)
          cached <- redis.get(s"game:$gameId")
        } yield {
          result shouldBe None
          cached shouldBe None
        }
      }
    }

    "fall back to Postgres and re-warm the cache when the cached value is corrupt" in {
      val (gameId, game) = newGame()
      val stub = new StubGameRepository(Map(gameId -> (GameType.TicTacToe, game)))

      withResources(stub) { (repo, redis) =>
        for {
          _ <- redis.set(s"game:$gameId", "not-valid-json")
          result <- repo.loadGame(gameId, GameType.TicTacToe)
          cached <- redis.get(s"game:$gameId")
        } yield {
          result shouldBe Some(game)
          stub.loadGameCallCount shouldBe 1
          cached shouldBe Some(GameTypeCodecs.serializeGame(GameType.TicTacToe, game))
        }
      }
    }
  }

  "RedisGameRepository.loadAllGames" should {
    "load all games from Postgres and warm the Redis cache" in {
      val (gameId, game) = newGame()
      val stub = new StubGameRepository(Map(gameId -> (GameType.TicTacToe, game)))

      withResources(stub) { (repo, redis) =>
        for {
          result <- repo.loadAllGames()
          cached <- redis.get(s"game:$gameId")
        } yield {
          result.get(gameId) shouldBe Some((GameType.TicTacToe, game))
          cached shouldBe Some(GameTypeCodecs.serializeGame(GameType.TicTacToe, game))
        }
      }
    }
  }
}
