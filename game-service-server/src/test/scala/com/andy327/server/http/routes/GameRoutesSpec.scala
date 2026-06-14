package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameId, GameType}
import com.andy327.server.actors.core.{GameManager, InMemRepo, PlayerActor}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.GridGameState
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.{LobbyMetadata, LobbyRepository, Player}
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class GameRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
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
    ActorSystem(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo), "GameRoutesSpecSystem")

  private val routes = concat(
    new LobbyRoutes(typedSystem).routes,
    new GameRoutes(GameType.TicTacToe, typedSystem).routes,
    new GameRoutes(GameType.ConnectFour, typedSystem).routes
  )

  val (aliceId, bobId, carlId) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

  val alicePlayer: Player = Player(aliceId, "alice")
  val bobPlayer: Player = Player(bobId, "bob")
  val carlPlayer: Player = Player(carlId, "carl")

  val aliceHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(alicePlayer)}")
  val bobHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(bobPlayer)}")
  val carlHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(carlPlayer)}")

  "GameRoutes" when {
    "handling TicTacToe" should {
      "submit a move to a valid game" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        Post(s"/tictactoe/$gameId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = io.circe.parser.decode[GridGameState](responseAs[String]).toOption.get
          gameState.board(0)(0) shouldBe "X"
        }
      }

      "fail to move in a nonexistent game" in {
        val fakeId = UUID.randomUUID()
        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")
        Post(s"/tictactoe/$fakeId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
        }
      }

      "return 409 when a move is rejected by the game" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }
        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        Post(s"/tictactoe/$gameId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        // alice moves again out of turn — rejected with 409, not 404
        Post(s"/tictactoe/$gameId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.Conflict
          responseAs[String] should include("not your turn")
        }
      }

      "fetch game status" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Get(s"/tictactoe/$gameId/status") ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = io.circe.parser.decode[GridGameState](responseAs[String]).toOption.get
          gameState.currentPlayer shouldBe "X"
        }
      }

      "return 404 for move on unknown game" in {
        val fakeId = UUID.randomUUID()
        Get(s"/tictactoe/$fakeId/status") ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
        }
      }

      "return 400 for invalid game ID on move" in {
        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        Post("/tictactoe/invalid-game-id/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      "return 400 for invalid JSON structure on move" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val invalidJson = """{ "x": 0, "y": 1 }"""
        val entity = HttpEntity(ContentTypes.`application/json`, invalidJson)

        Post(s"/tictactoe/$gameId/move", entity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("Invalid JSON")
        }
      }

      "return 404 for status of unknown game" in {
        val fakeId = UUID.randomUUID()
        Get(s"/tictactoe/$fakeId/status") ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
        }
      }

      "return 400 for invalid game ID on status" in
        Get("/tictactoe/invalid-game-id/status") ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }

      "return 500 for unexpected responses" in {
        val fakeId = UUID.randomUUID()
        val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
          case GameManager.RunGameOperation(_, _, replyTo) =>
            replyTo ! GameManager.Ready // triggers /move + /status fallback
            Behaviors.same

          case GameManager.SubscribePlayerToGame(_, _, replyTo) =>
            replyTo ! GameManager.Ready // triggers /subscribe fallback
            Behaviors.same

          case _ => Behaviors.same
        }

        val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseSystem")
        val errorRoutes = new GameRoutes(GameType.TicTacToe, dummySystem).routes

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        val requests = Table(
          ("description", "request"),
          ("POST /move", Post(s"/tictactoe/$fakeId/move", moveEntity).withHeaders(aliceHeader)),
          ("GET /status", Get(s"/tictactoe/$fakeId/status")),
          ("POST /subscribe", Post(s"/tictactoe/$fakeId/subscribe").withHeaders(aliceHeader))
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

      "return 409 for MoveRejected and 500 for ErrorResponse on move and status routes" in {
        // MoveRejected on /status and ErrorResponse on either route cannot be produced through the real
        // GameManager from these endpoints, so a stub behavior forces them to cover the route mappings.
        val fakeId = UUID.randomUUID()
        def stubSystem(response: GameManager.GameResponse): ActorSystem[GameManager.Command] =
          ActorSystem(
            Behaviors.receiveMessage[GameManager.Command] {
              case GameManager.RunGameOperation(_, _, replyTo) =>
                replyTo ! response
                Behaviors.same
              case _ => Behaviors.same
            },
            s"StubResponseSystem${UUID.randomUUID().toString.take(8)}"
          )

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        val rejectedRoutes = new GameRoutes(GameType.TicTacToe, stubSystem(GameManager.MoveRejected("no way"))).routes
        Post(s"/tictactoe/$fakeId/move", moveEntity).withHeaders(aliceHeader) ~> rejectedRoutes ~> check {
          status shouldBe StatusCodes.Conflict
          responseAs[String] should include("no way")
        }
        Get(s"/tictactoe/$fakeId/status") ~> rejectedRoutes ~> check {
          status shouldBe StatusCodes.Conflict
        }

        val errorRoutes = new GameRoutes(GameType.TicTacToe, stubSystem(GameManager.ErrorResponse("boom"))).routes
        Post(s"/tictactoe/$fakeId/move", moveEntity).withHeaders(aliceHeader) ~> errorRoutes ~> check {
          status shouldBe StatusCodes.InternalServerError
          responseAs[String] should include("boom")
        }
        Get(s"/tictactoe/$fakeId/status") ~> errorRoutes ~> check {
          status shouldBe StatusCodes.InternalServerError
        }
      }

      "return 200 when subscribing to an active game with a WebSocket connection" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }
        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val wsProbe = testKit.createTestProbe[PlayerActor.WsOutput]()
        val aliceRef = Await.result(
          typedSystem.ask[ActorRef[PlayerActor.Command]](GameManager.RegisterPlayer(alicePlayer, wsProbe.ref, _)),
          3.seconds
        )

        Post(s"/tictactoe/$gameId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.SubscribeAcknowledged].gameId shouldBe gameId
        }

        typedSystem ! GameManager.PlayerDisconnected(alicePlayer.id, aliceRef)
      }

      "return 400 when subscribing to a game without an active WebSocket connection" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }
        Post("/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check(())
        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check(())

        // Alice has no WebSocket connection registered, so the subscribe will fail
        Post(s"/tictactoe/$gameId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("not connected")
        }
      }

      "return 401 when subscribing to a game without authentication" in {
        val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/tictactoe/$gameId/subscribe") ~> routes ~> check {
          status shouldBe StatusCodes.Unauthorized
        }
      }
    }

    "handling ConnectFour" should {
      "submit a move to a valid game" in {
        val gameId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"col":3}""")

        Post(s"/connectfour/$gameId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = io.circe.parser.decode[GridGameState](responseAs[String]).toOption.get
          gameState.board(5)(3) shouldBe "R"
        }
      }

      "fail to move in a nonexistent game" in {
        val fakeId = UUID.randomUUID()
        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"col":3}""")
        Post(s"/connectfour/$fakeId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
        }
      }

      "fetch game status" in {
        val gameId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Get(s"/connectfour/$gameId/status") ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = io.circe.parser.decode[GridGameState](responseAs[String]).toOption.get
          gameState.currentPlayer shouldBe "R"
        }
      }

      "return 404 for status of unknown game" in {
        val fakeId = UUID.randomUUID()
        Get(s"/connectfour/$fakeId/status") ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
        }
      }

      "return 400 for invalid game ID on move" in {
        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"col":3}""")

        Post("/connectfour/invalid-game-id/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      "return 400 for invalid JSON structure on move" in {
        val gameId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val invalidJson = """{ "row": 0, "column": 3 }"""
        val entity = HttpEntity(ContentTypes.`application/json`, invalidJson)

        Post(s"/connectfour/$gameId/move", entity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("Invalid JSON")
        }
      }

      "return 400 for invalid game ID on status" in
        Get("/connectfour/invalid-game-id/status") ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }

      "return 500 for unexpected responses" in {
        val fakeId = UUID.randomUUID()
        val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
          case GameManager.RunGameOperation(_, _, replyTo) =>
            replyTo ! GameManager.Ready
            Behaviors.same

          case GameManager.SubscribePlayerToGame(_, _, replyTo) =>
            replyTo ! GameManager.Ready
            Behaviors.same

          case _ => Behaviors.same
        }

        val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseCFSystem")
        val errorRoutes = new GameRoutes(GameType.ConnectFour, dummySystem).routes

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"col":3}""")

        val requests = Table(
          ("description", "request"),
          ("POST /move", Post(s"/connectfour/$fakeId/move", moveEntity).withHeaders(aliceHeader)),
          ("GET /status", Get(s"/connectfour/$fakeId/status")),
          ("POST /subscribe", Post(s"/connectfour/$fakeId/subscribe").withHeaders(aliceHeader))
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

      "return 200 when subscribing to an active game with a WebSocket connection" in {
        val gameId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }
        Post(s"/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val wsProbe = testKit.createTestProbe[PlayerActor.WsOutput]()
        val aliceRef = Await.result(
          typedSystem.ask[ActorRef[PlayerActor.Command]](GameManager.RegisterPlayer(alicePlayer, wsProbe.ref, _)),
          3.seconds
        )

        Post(s"/connectfour/$gameId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.SubscribeAcknowledged].gameId shouldBe gameId
        }

        typedSystem ! GameManager.PlayerDisconnected(alicePlayer.id, aliceRef)
      }

      "return 400 when subscribing to a game without an active WebSocket connection" in {
        val gameId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }
        Post("/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check(())
        Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check(())

        Post(s"/connectfour/$gameId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("not connected")
        }
      }

      "return 401 when subscribing to a game without authentication" in {
        val gameId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].gameId
        }

        Post(s"/connectfour/$gameId/subscribe") ~> routes ~> check {
          status shouldBe StatusCodes.Unauthorized
        }
      }
    }
  }
}
