package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.ws.Message
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.{GameManager, InMemRepo, PlayerActor}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.{GameLifecycleStatus, LobbyMetadata, Player}
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class LobbyRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val runtime: IORuntime = IORuntime.global
  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit lazy val scheduler = typedSystem.scheduler

  private val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo), "LobbyRoutesSpecSystem")

  private val routes = new LobbyRoutes(typedSystem).routes

  val (aliceId, bobId, carlId) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

  val alicePlayer: Player = Player(aliceId, "alice")
  val bobPlayer: Player = Player(bobId, "bob")
  val carlPlayer: Player = Player(carlId, "carl")

  val aliceHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(alicePlayer)}")
  val bobHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(bobPlayer)}")
  val carlHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(carlPlayer)}")

  "LobbyRoutes" should {
    "create a new lobby" in
      Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val GameManager.LobbyCreated(gameId, host) = responseAs[GameManager.LobbyCreated]
        gameId.toString.length should be > 0
        host.name shouldBe "alice"
        host.id shouldBe aliceId
      }

    "reject creating a lobby with an invalid game type" in
      Post("/lobby/create/unknowngame").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Invalid game type")
      }

    "join a lobby with a second player" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val GameManager.LobbyJoined(_, metadata, joinedPlayer) = responseAs[GameManager.LobbyJoined]
        metadata.players.values.toSet should contain only (alicePlayer, joinedPlayer)
      }
    }

    "not allow too many players to join a lobby" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/lobby/$gameId/join").withHeaders(carlHeader) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
      }
    }

    "leave a lobby" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      // Bob leaves — lobby drops from ReadyToStart back to WaitingForPlayers
      Post(s"/lobby/$gameId/leave").withHeaders(bobHeader) ~> routes ~> check {
        val leftLobby = responseAs[GameManager.LobbyLeft]
        leftLobby.gameId shouldBe gameId
      }

      Get(s"/lobby/$gameId") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[LobbyMetadata].status shouldBe GameLifecycleStatus.WaitingForPlayers
      }

      // Bob tries to leave again (OK)
      Post(s"/lobby/$gameId/leave").withHeaders(bobHeader) ~> routes ~> check {
        val leftLobby = responseAs[GameManager.LobbyLeft]
        leftLobby.gameId shouldBe gameId
      }

      // Alice (host) leaves
      Post(s"/lobby/$gameId/leave").withHeaders(aliceHeader) ~> routes ~> check {
        val leftLobby = responseAs[GameManager.LobbyLeft]
        leftLobby.gameId shouldBe gameId
      }

      // Game should be cancelled
      Get(s"/lobby/$gameId") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[LobbyMetadata]
        response.status shouldBe GameLifecycleStatus.Cancelled
      }
    }

    "reject leaving a lobby if the lobby does not exist" in {
      val fakeId = UUID.randomUUID()

      Post(s"/lobby/$fakeId/leave").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[String] should include("No such lobby")
      }
    }

    "reject leaving a lobby if the gameId is not a valid UUID" in
      Post("/lobby/not-a-uuid/leave").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Invalid UUID for game")
      }

    "start a game after a lobby has enough players" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[GameManager.GameStarted].gameId shouldBe gameId
      }
    }

    "reject starting a game if the requester is not the host" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/lobby/$gameId/start").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.Forbidden
        responseAs[String] should include("Only the host can start")
      }
    }

    "reject starting a game with not enough players" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.Conflict
        responseAs[String] should include("does not have enough players")
      }
    }

    "return metadata for a valid lobby" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val expectedLobbyMetadata = LobbyMetadata(
        gameId = gameId,
        gameType = GameType.TicTacToe,
        players = Map(aliceId -> alicePlayer),
        hostId = aliceId,
        status = GameLifecycleStatus.WaitingForPlayers
      )

      Get(s"/lobby/$gameId") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val response = responseAs[LobbyMetadata]
        response shouldBe expectedLobbyMetadata
      }
    }

    "reject a lobby info request if the gameId is not a valid UUID" in
      Get("/lobby/not-a-uuid") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Invalid UUID for game")
      }

    "reject a lobby info request if the lobby does not exist" in {
      val fakeId = UUID.randomUUID()

      Get(s"/lobby/$fakeId") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[String] should include("No such lobby")
      }
    }

    "list all lobbies with pagination metadata" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Get("/lobby/list") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val result = responseAs[GameManager.LobbiesListed]
        result.lobbies.map(_.gameId) should contain(gameId)
        result.page shouldBe 1
        result.limit shouldBe 20
        result.total should be >= 1
      }
    }

    "filter lobbies by game type" in {
      Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Get("/lobby/list?gameType=tictactoe") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val result = responseAs[GameManager.LobbiesListed]
        result.lobbies.foreach(_.gameType shouldBe GameType.TicTacToe)
      }
    }

    "return 400 for an unknown game type filter" in
      Get("/lobby/list?gameType=unknowngame") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Unknown game type")
      }

    "return 400 for page < 1" in
      Get("/lobby/list?page=0") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("page must be >= 1")
      }

    "return 400 for limit out of range" in
      Get("/lobby/list?limit=0") ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("limit must be between 1 and 100")
      }

    "return 500 for unexpected responses" in {
      val fakeId = UUID.randomUUID()
      val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
        case GameManager.CreateLobby(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /create fallback
          Behaviors.same

        case GameManager.JoinLobby(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /join fallback
          Behaviors.same

        case GameManager.LeaveLobby(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /join fallback
          Behaviors.same

        case GameManager.StartGame(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /start fallback
          Behaviors.same

        case GameManager.ListLobbies(_, _, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /list fallback
          Behaviors.same

        case GameManager.GetLobbyInfo(_, replyTo) =>
          replyTo ! GameManager.Ready // triggers /join fallback
          Behaviors.same

        case GameManager.SubscribePlayerToLobby(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /subscribe fallback
          Behaviors.same

        case _ => Behaviors.same
      }

      val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseSystem")
      val errorRoutes = new LobbyRoutes(dummySystem).routes

      val requests = Table(
        ("description", "request"),
        ("POST /lobby/create", Post("/lobby/create/tictactoe").withHeaders(aliceHeader)),
        ("POST /lobby/join", Post(s"/lobby/$fakeId/join").withHeaders(aliceHeader)),
        ("POST /lobby/leave", Post(s"/lobby/$fakeId/leave").withHeaders(aliceHeader)),
        ("POST /lobby/start", Post(s"/lobby/$fakeId/start").withHeaders(aliceHeader)),
        ("POST /lobby/subscribe", Post(s"/lobby/$fakeId/subscribe").withHeaders(aliceHeader)),
        ("GET /lobby", Get(s"/lobby/$fakeId").withHeaders(aliceHeader)),
        ("GET /lobby/list", Get("/lobby/list"))
      )

      forAll(requests) { (description, req) =>
        withClue(s"$description should return 500 with fallback message") {
          req ~> errorRoutes ~> check {
            status shouldBe StatusCodes.InternalServerError
            responseAs[String] should include("Unexpected")
          }
        }
      }
    }

    "return 200 when subscribing to a lobby with an active WebSocket connection" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val wsProbe = testKit.createTestProbe[Message]()
      Await.result(
        typedSystem.ask[ActorRef[PlayerActor.Command]](GameManager.RegisterPlayer(alicePlayer, wsProbe.ref, _)),
        3.seconds
      )

      Post(s"/lobby/$gameId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[GameManager.SubscribeAcknowledged].gameId shouldBe gameId
      }

      typedSystem ! GameManager.PlayerDisconnected(alicePlayer.id)
    }

    "return 400 when subscribing to a lobby without an active WebSocket connection" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      // Alice has no WebSocket connection registered, so the subscribe will fail
      Post(s"/lobby/$gameId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("not connected")
      }
    }

    "return 401 when subscribing to a lobby without authentication" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/subscribe") ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }
  }
}
