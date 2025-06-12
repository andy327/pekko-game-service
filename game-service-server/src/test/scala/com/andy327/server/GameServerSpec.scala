package com.andy327.server

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{Game, GameType}
import com.andy327.persistence.db.GameRepository

class GameServerSpec extends AnyWordSpec with Matchers {
  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val runtime: IORuntime = IORuntime.global

  "GameServer" should {
    "start and respond to /tictactoe" in {
      val dummyRepo = new GameRepository {
        def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] = IO.pure(Map.empty)
        def loadGame(id: String, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def saveGame(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = IO.unit
      }

      val (system, binding) = GameServer.startServer("localhost", port = 0, dummyRepo).unsafeRunSync()
      val actualPort = binding.localAddress.getPort
      implicit val classicSystem: ActorSystem = system.classicSystem

      try {
        val request = HttpRequest(
          method = HttpMethods.POST,
          uri = s"http://localhost:${actualPort}/tictactoe?playerX=alice&playerO=bob"
        )
        val responseFuture: Future[HttpResponse] = Http()(classicSystem).singleRequest(request)
        val response = Await.result(responseFuture, 5.seconds)

        response.status shouldBe StatusCodes.OK
      } finally {
        Await.result(binding.unbind(), 5.seconds)
        system.terminate()
      }
    }
  }
}
