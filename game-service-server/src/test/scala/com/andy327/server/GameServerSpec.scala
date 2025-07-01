package com.andy327.server

import scala.concurrent.Await
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.ActorSystem
import org.apache.pekko.http.scaladsl.Http
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.unmarshalling.Unmarshal
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{Game, GameId, GameType}
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.core.GameManager
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.Player
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class GameServerSpec extends AnyWordSpec with Matchers {
  implicit val timeout: Timeout = Timeout(5.seconds)
  implicit val runtime: IORuntime = IORuntime.global

  "GameServer" should {
    "start and respond to /tictactoe" in {
      val dummyRepo = new GameRepository {
        def initialize(): IO[Unit] = IO.unit
        def loadGame(gameId: GameId, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
        def saveGame(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = IO.unit
        def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] = IO.pure(Map.empty)
      }

      val (system, binding) = GameServer.startServer("localhost", port = 0, dummyRepo).unsafeRunSync()
      val actualPort = binding.localAddress.getPort
      implicit val classicSystem: ActorSystem = system.classicSystem

      val player1 = Player("alice")
      val player2 = Player("bob")

      val player1Header = RawHeader("Authorization", s"Bearer ${createTestToken(player1)}")
      val player2Header = RawHeader("Authorization", s"Bearer ${createTestToken(player2)}")

      try {
        // Create lobby with player 1
        val createLobbyReq = HttpRequest(
          method = HttpMethods.POST,
          uri = s"http://localhost:${actualPort}/lobby/create/tictactoe"
        ).withHeaders(player1Header)
        val createLobbyResp = Await.result(Http()(classicSystem).singleRequest(createLobbyReq), 2.seconds)
        val gameId = Await.result(Unmarshal(createLobbyResp.entity).to[GameManager.LobbyCreated], 2.seconds).gameId
        createLobbyResp.status shouldBe StatusCodes.OK

        // Join lobby with player 2
        val joinLobbyReq = HttpRequest(
          method = HttpMethods.POST,
          uri = s"http://localhost:${actualPort}/lobby/${gameId}/join"
        ).withHeaders(player2Header)
        val joinLobbyResp = Await.result(Http()(classicSystem).singleRequest(joinLobbyReq), 2.seconds)
        joinLobbyResp.status shouldBe StatusCodes.OK

        // Start the game from the lobby (initiated by the host)
        val startGameReq = HttpRequest(
          method = HttpMethods.POST,
          uri = s"http://localhost:${actualPort}/lobby/${gameId}/start"
        ).withHeaders(player1Header)
        val startResp = Await.result(Http()(classicSystem).singleRequest(startGameReq), 2.seconds)
        startResp.status shouldBe StatusCodes.OK

        val startGameResp = Await.result(Unmarshal(startResp.entity).to[GameManager.GameStarted], 2.seconds)
        startGameResp.gameId shouldBe gameId
      } finally {
        Await.result(binding.unbind(), 5.seconds)
        system.terminate()
      }
    }
  }
}
