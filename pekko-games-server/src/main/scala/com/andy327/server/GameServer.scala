package com.andy327.server

import actors.GameManager
import routes.TicTacToeRoutes
import com.typesafe.config.ConfigFactory
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.Http

import scala.concurrent.{Await, ExecutionContextExecutor, Future}
import scala.concurrent.duration._
import scala.util.{Failure, Success}

object GameServer extends App {
  // Load configuration
  val config = ConfigFactory.load()
  val host = config.getString("pekko-games.http.host")
  val port = config.getInt("pekko-games.http.port")

  implicit val system: ActorSystem[GameManager.Command] = ActorSystem(GameManager(), "GameManagerSystem")
  implicit val ec: ExecutionContextExecutor = system.executionContext

  val routes = new TicTacToeRoutes(system).routes

  val bindingFuture: Future[Http.ServerBinding] =
    Http().newServerAt(host, port).bind(routes)

  bindingFuture.onComplete {
    case Success(binding) =>
      val address = binding.localAddress
      println(s"Server online at http://${address.getHostString}:${address.getPort}/")
    case Failure(ex) =>
      println(s"Failed to bind HTTP endpoint, terminating system: ${ex.getMessage}")
      system.terminate()
  }

  // graceful shutdown on Ctrl+C
  sys.addShutdownHook {
    println("Shutting down server...")
    system.classicSystem.terminate()
  }

  // Keep the application alive
  Await.result(system.whenTerminated, Duration.Inf)
}
