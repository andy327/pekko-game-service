package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameId, GameType}
import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.auth.PlayerSessionsResponse
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.{LobbyMetadata, LobbyRepository, Player}
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class PlayerRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val runtime: IORuntime = IORuntime.global
  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit lazy val scheduler: Scheduler = typedSystem.scheduler

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(gameId: GameId): IO[Unit] = IO.unit
    override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
  }

  private val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo), "PlayerRoutesSpecSystem")

  private val routes = new PlayerRoutes(typedSystem).routes

  private val alice: Player = Player(UUID.randomUUID(), "alice")
  private val bob: Player = Player(UUID.randomUUID(), "bob")

  private val aliceHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(alice)}")

  /** Drives lobby/game setup directly through the GameManager, bypassing the lobby HTTP routes. */
  private def ask(
      make: org.apache.pekko.actor.typed.ActorRef[GameManager.GameResponse] => GameManager.Command
  ): GameManager.GameResponse =
    Await.result(typedSystem.ask[GameManager.GameResponse](make), 3.seconds)

  "PlayerRoutes" should {
    "return empty sessions for a player who is in nothing" in
      Get("/players/me/sessions").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val sessions = responseAs[PlayerSessionsResponse]
        sessions.lobbies shouldBe empty
        sessions.games shouldBe empty
      }

    "reject an unauthenticated request" in
      Get("/players/me/sessions") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }

    "report the player's joined lobby and active game" in {
      // Lobby A: alice + bob, left joinable
      val GameManager.LobbyCreated(lobbyA, _) = ask(GameManager.CreateLobby(GameType.TicTacToe, alice, _))
      ask(GameManager.JoinLobby(lobbyA, bob, _))

      // Lobby B: alice + bob, started into a live game
      val GameManager.LobbyCreated(gameB, hostB) = ask(GameManager.CreateLobby(GameType.TicTacToe, alice, _))
      ask(GameManager.JoinLobby(gameB, bob, _))
      ask(GameManager.StartGame(gameB, hostB.id, _))

      Get("/players/me/sessions").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val sessions = responseAs[PlayerSessionsResponse]
        sessions.lobbies.map(_.gameId) should contain(lobbyA)
        sessions.lobbies.map(_.gameId) should not contain gameB
        sessions.games.map(_.gameId) should contain(gameB)
        sessions.games.find(_.gameId == gameB).map(_.gameType) shouldBe Some(GameType.TicTacToe)
      }
    }

    "return 500 when the GameManager replies with an unexpected response" in {
      val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
        case GameManager.GetPlayerSessions(_, replyTo) =>
          replyTo ! GameManager.Ready // a valid GameResponse, but not a PlayerSessions → triggers the fallback
          Behaviors.same
        case _ => Behaviors.same
      }
      val dummySystem = ActorSystem(unexpectedBehavior, "PlayerRoutesUnexpectedResponseSystem")
      val errorRoutes = new PlayerRoutes(dummySystem).routes

      Get("/players/me/sessions").withHeaders(aliceHeader) ~> errorRoutes ~> check {
        status shouldBe StatusCodes.InternalServerError
        responseAs[String] should include("Unexpected")
      }
    }
  }
}
