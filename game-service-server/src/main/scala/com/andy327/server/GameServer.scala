package com.andy327.server

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.slf4j.LoggerFactory

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.prometheus.client.CollectorRegistry
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._

import com.andy327.actor.chat.{ChatRepository, NoOpChatRepository, RedisChatRepository}
import com.andy327.actor.core.GameManager
import com.andy327.actor.events.{EventPublisher, NoOpEventPublisher}
import com.andy327.actor.lobby.{LobbyRepository, RedisLobbyRepository}
import com.andy327.actor.persistence.PostgresActor
import com.andy327.model.core.GameType
import com.andy327.persistence.db.postgres.{
  PostgresGameRepository,
  PostgresMoveHistoryRepository,
  PostgresPlayerHistoryRepository,
  PostgresTransactor,
  PostgresUserRepository
}
import com.andy327.persistence.db.redis.{RedisClientResource, RedisGameRepository}
import com.andy327.persistence.db.{
  GameRepository,
  InMemoryPlayerHistoryRepository,
  InMemoryUserRepository,
  MoveHistoryRepository,
  PlayerHistoryRepository
}
import com.andy327.server.analytics.{AnalyticsConsumer, GameMetrics, RedisAnalyticsPublisher}
import com.andy327.server.auth.{IdentityProvider, PasswordHasher, PasswordIdentityProvider}
import com.andy327.server.http.routes.{
  AuthRoutes,
  GameRoutes,
  LobbyRoutes,
  MetricsRoutes,
  PlayerRoutes,
  StaticRoutes,
  TraceRoutes,
  WebSocketRoutes
}
import com.andy327.server.pubsub.RedisPubSubResource

/** GameServer is the main entry point of the game-service. It initializes the database, actor system, and HTTP server.
  */
object GameServer {
  private val logger = LoggerFactory.getLogger(getClass)

  // $COVERAGE-OFF$ bootstrap wiring and JVM entry point
  def main(args: Array[String]): Unit = {
    // Load configuration from application.conf
    val config: Config = ConfigFactory.load()
    val host: String = config.getString("pekko-game-service.http.host")
    val port: Int = config.getInt("pekko-game-service.http.port")

    implicit val runtime: IORuntime = IORuntime.global

    // Compose Postgres transactor, Redis commands, and Redis pub/sub as a single managed resource
    val resources = for {
      xa <- PostgresTransactor(config)
      redis <- RedisClientResource(config)
      pubSub <- RedisPubSubResource(config)
    } yield (xa, redis, pubSub)

    resources.use { case (xa, redis, (publishFn, subscribeStream)) =>
      val gameRepo = new RedisGameRepository(new PostgresGameRepository(xa), redis)
      val lobbyRepo = new RedisLobbyRepository(redis)
      val moveRepo = new PostgresMoveHistoryRepository(xa)
      val playerHistoryRepo = new PostgresPlayerHistoryRepository(xa)
      val chatRepo = new RedisChatRepository(redis, config.getInt("pekko-game-service.chat.max-messages"))
      val userRepo = new PostgresUserRepository(xa)
      val identityProvider = new PasswordIdentityProvider(userRepo, PasswordHasher.fromConfig())

      // Analytics: publisher emits to the game-analytics channel; consumer folds events into the /metrics registry
      val registry = new CollectorRegistry()
      val metrics = new GameMetrics(registry)
      val publisher = new RedisAnalyticsPublisher(publishFn)
      val consumer = new AnalyticsConsumer(subscribeStream, metrics)

      // Ensure the schema exists before starting the server
      for {
        _ <- gameRepo.initialize()
        _ <- moveRepo.initialize()
        _ <- playerHistoryRepo.initialize()
        _ <- userRepo.initialize()
        // Run the analytics consumer as a background fiber; it stays alive for the lifetime of the server
        _ <- consumer.run.start
        result <- startServer(
          host,
          port,
          gameRepo,
          lobbyRepo,
          moveRepo,
          chatRepo,
          playerHistoryRepo,
          identityProvider,
          publisher,
          registry
        ).flatMap { case (system, _) =>
          IO.blocking(Await.result(system.whenTerminated, Duration.Inf))
        }
      } yield result
    }.unsafeRunSync()
  }

  /** Starts the actor system and HTTP server and returns both. Can be reused in tests. */
  def startServer(
      host: String,
      port: Int,
      gameRepo: GameRepository,
      lobbyRepo: LobbyRepository,
      moveRepo: MoveHistoryRepository,
      chatRepo: ChatRepository = NoOpChatRepository,
      playerHistoryRepo: PlayerHistoryRepository = new InMemoryPlayerHistoryRepository,
      identityProvider: IdentityProvider =
        new PasswordIdentityProvider(new InMemoryUserRepository, PasswordHasher.fromConfig()),
      publisher: EventPublisher = NoOpEventPublisher,
      metricsRegistry: CollectorRegistry = new CollectorRegistry()
  )(implicit runtime: IORuntime): IO[(ActorSystem[GameManager.Command], Http.ServerBinding)] = IO.defer {
    val rootBehavior = Behaviors.setup[GameManager.Command] { context =>
      val persistActor = context.spawn(PostgresActor(gameRepo, moveRepo, playerHistoryRepo), "postgres-persistence")
      GameManager(persistActor, gameRepo, lobbyRepo, moveRepo, chatRepo, publisher)
    }

    // Pekko actor system
    val system: ActorSystem[GameManager.Command] = ActorSystem(rootBehavior, "GameManagerSystem")
    implicit val classicSystem = system.classicSystem // Pekko HTTP still requires the classic ActorSystem

    // HTTP routes
    val routes = concat(
      new AuthRoutes(identityProvider).routes,
      new PlayerRoutes(system, playerHistoryRepo).routes,
      new LobbyRoutes(system).routes,
      new GameRoutes(GameType.TicTacToe, system).routes,
      new GameRoutes(GameType.ConnectFour, system).routes,
      new GameRoutes(GameType.Battleship, system).routes,
      new GameRoutes(GameType.Pig, system).routes,
      new WebSocketRoutes(system).routes,
      new TraceRoutes(system).routes,
      new MetricsRoutes(metricsRegistry).routes,
      new StaticRoutes().routes // Web UI; composed last so its catch-all resource lookup doesn't shadow an API route
    )

    // Start HTTP server
    val bindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt(host, port).bind(routes)

    // Handle server startup result
    IO.fromFuture(IO(bindingFuture)).flatTap { binding =>
      IO.delay {
        val address = binding.localAddress
        logger.info(s"Server online at http://${address.getHostString}:${address.getPort}/")

        // JVM shutdown hook for graceful shutdown
        sys.addShutdownHook {
          logger.info("Shutting down server...")
          binding.unbind()
          system.terminate()
        }
      }
    }.map(binding => (system, binding))
  }
  // $COVERAGE-ON$
}
