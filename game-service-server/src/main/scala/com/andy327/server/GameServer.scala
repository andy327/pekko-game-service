package com.andy327.server

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import org.slf4j.LoggerFactory

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}

import doobie.Transactor
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.server.Directives._

import com.andy327.persistence.db.GameRepository
import com.andy327.persistence.db.postgres.{PostgresGameRepository, PostgresTransactor}
import com.andy327.server.actors.core.GameManager
import com.andy327.server.actors.persistence.PostgresActor
import com.andy327.server.http.routes.{AuthRoutes, LobbyRoutes, TicTacToeRoutes}

/**
 * GameServer is the main entry point of the game-service.
 * It initializes the database, actor system, and HTTP server.
 */
object GameServer {
  private val logger = LoggerFactory.getLogger(getClass)

  def main(args: Array[String]): Unit = {
    // Load configuration from application.conf
    val config: Config = ConfigFactory.load()
    val host: String = config.getString("pekko-game-service.http.host")
    val port: Int = config.getInt("pekko-game-service.http.port")

    implicit val runtime: IORuntime = IORuntime.global

    // Database transactor resource to manage thread and connection pooling
    val transactorResource: Resource[IO, Transactor[IO]] = PostgresTransactor(config)

    // Use the transactor resource to initialize the rest of the app
    transactorResource.use { xa =>
      // Initialize repository with transactor
      val gameRepository = new PostgresGameRepository(xa)

      // Ensure the schema exists before starting the server
      for {
        _ <- gameRepository.initialize()
        result <- startServer(host, port, gameRepository).flatMap { case (system, _) =>
          IO.blocking(Await.result(system.whenTerminated, Duration.Inf))
        }
      } yield result
    }.unsafeRunSync()
  }

  /**
   * Starts the actor system and HTTP server and returns both.
   * Can be reused in tests.
   */
  def startServer(
      host: String,
      port: Int,
      gameRepo: GameRepository
  ): IO[(ActorSystem[GameManager.Command], Http.ServerBinding)] = IO.defer {
    val rootBehavior = Behaviors.setup[GameManager.Command] { context =>
      val persistActor = context.spawn(PostgresActor(gameRepo), "postgres-persistence")
      GameManager(persistActor, gameRepo)
    }

    // Pekko actor system
    val system: ActorSystem[GameManager.Command] = ActorSystem(rootBehavior, "GameManagerSystem")
    implicit val classicSystem = system.classicSystem // Pekko HTTP still requires the classic ActorSystem

    // HTTP routes
    val routes = concat(
      new AuthRoutes().routes,
      new LobbyRoutes(system).routes,
      new TicTacToeRoutes(system).routes
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
}
