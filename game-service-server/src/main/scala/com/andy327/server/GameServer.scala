package com.andy327.server

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.slf4j.LoggerFactory

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._

import com.andy327.model.core.GameType
import com.andy327.persistence.db.GameRepository
import com.andy327.persistence.db.postgres.{PostgresGameRepository, PostgresTransactor}
import com.andy327.persistence.db.redis.{RedisClientResource, RedisGameRepository}
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.persistence.PostgresActor
import com.andy327.server.http.routes.{AuthRoutes, GameRoutes, LobbyRoutes, WebSocketRoutes}
import com.andy327.server.lobby.{LobbyRepository, RedisLobbyRepository}

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

    // Compose Postgres transactor and Redis commands as a single managed resource
    val resources = for {
      xa <- PostgresTransactor(config)
      redis <- RedisClientResource(config)
    } yield (xa, redis)

    resources.use { case (xa, redis) =>
      val postgresRepo = new PostgresGameRepository(xa)
      val gameRepo = new RedisGameRepository(postgresRepo, redis)
      val lobbyRepo = new RedisLobbyRepository(redis)

      // Ensure the schema exists before starting the server
      for {
        _ <- gameRepo.initialize()
        result <- startServer(host, port, gameRepo, lobbyRepo).flatMap { case (system, _) =>
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
      lobbyRepo: LobbyRepository
  )(implicit runtime: IORuntime): IO[(ActorSystem[GameManager.Command], Http.ServerBinding)] = IO.defer {
    val rootBehavior = Behaviors.setup[GameManager.Command] { context =>
      val persistActor = context.spawn(PostgresActor(gameRepo), "postgres-persistence")
      GameManager(persistActor, gameRepo, lobbyRepo)
    }

    // Pekko actor system
    val system: ActorSystem[GameManager.Command] = ActorSystem(rootBehavior, "GameManagerSystem")
    implicit val classicSystem = system.classicSystem // Pekko HTTP still requires the classic ActorSystem

    // HTTP routes
    val routes = concat(
      new AuthRoutes().routes,
      new LobbyRoutes(system).routes,
      new GameRoutes(GameType.TicTacToe, system).routes,
      new GameRoutes(GameType.ConnectFour, system).routes,
      new WebSocketRoutes(system).routes
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
