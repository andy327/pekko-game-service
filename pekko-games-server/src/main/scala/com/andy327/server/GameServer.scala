package com.andy327.server

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.util.{Failure, Success}

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.unsafe.IORuntime
import cats.effect.{IO, Resource}

import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http

import com.andy327.persistence.db.postgres.{DatabaseTransactor, PostgresGameRepository}

import actors.core.GameManager
import http.routes.TicTacToeRoutes

/**
 * GameServer is the main entry point of the Tic-Tac-Toe backend service.
 * It initializes the database, actor system, and HTTP server.
 */
object GameServer extends App {
  // Load configuration from application.conf
  val config: Config = ConfigFactory.load()
  val host: String = config.getString("pekko-games.http.host")
  val port: Int = config.getInt("pekko-games.http.port")

  implicit val runtime: IORuntime = cats.effect.unsafe.IORuntime.global

  // Database transactor resource to manage thread and connection pooling
  val transactorResource: Resource[IO, doobie.Transactor[IO]] = DatabaseTransactor.transactor

  // Use the transactor resource to initialize the rest of the app
  transactorResource.use { xa =>
    // Initialize repository with transactor
    val gameRepository = new PostgresGameRepository(xa)

    // Pekko actor system
    implicit val system: ActorSystem[GameManager.Command] =
      ActorSystem(GameManager(gameRepository), "GameManagerSystem")
    implicit val ec: ExecutionContextExecutor = system.executionContext

    // HTTP routes
    val routes = new TicTacToeRoutes(system).routes

    // Start HTTP server
    val bindingFuture: Future[Http.ServerBinding] =
      Http().newServerAt(host, port).bind(routes)

    // Handle server startup result
    bindingFuture.onComplete {
      case Success(binding) =>
        val address = binding.localAddress
        println(s"Server online at http://${address.getHostString}:${address.getPort}/")
      case Failure(ex) =>
        println(s"Failed to bind HTTP endpoint, terminating system: ${ex.getMessage}")
        system.terminate()
    }

    // JVM shutdown hook for graceful shutdown
    sys.addShutdownHook {
      println("Shutting down server...")
      system.classicSystem.terminate()
    }

    // Keep the application alive
    IO.blocking(Await.result(system.whenTerminated, Duration.Inf))
  }.unsafeRunSync()
}
