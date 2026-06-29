package com.andy327.server.http.routes

import java.time.Instant
import java.util.UUID

import scala.concurrent.Await
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import io.circe.syntax._
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

import com.andy327.actor.core.{GameManager, InMemChatRepo, InMemMoveRepo, InMemRepo, PlayerActor, PlayerEvent}
import com.andy327.actor.game.GridGameState
import com.andy327.actor.lobby.{LobbyMetadata, LobbyRepository, Player}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.model.core.{GameType, RoomId}
import com.andy327.persistence.db.MoveRecord
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class GameRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val runtime: IORuntime = IORuntime.global
  implicit val timeout: Timeout = Timeout(3.seconds)
  implicit lazy val scheduler: Scheduler = typedSystem.scheduler

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(roomId: RoomId): IO[Unit] = IO.unit
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
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        Post(s"/tictactoe/$roomId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
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
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }
        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        Post(s"/tictactoe/$roomId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        // alice moves again out of turn — rejected with 409, not 404
        Post(s"/tictactoe/$roomId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.Conflict
          responseAs[String] should include("not your turn")
        }
      }

      "fetch game status" in {
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Get(s"/tictactoe/$roomId/status") ~> routes ~> check {
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

      "return the recorded move history" in {
        val roomId = UUID.randomUUID()
        val moves = List(
          MoveRecord(0, aliceId, Json.obj("row" -> 0.asJson, "col" -> 0.asJson), Instant.EPOCH),
          MoveRecord(1, bobId, Json.obj("row" -> 1.asJson, "col" -> 1.asJson), Instant.EPOCH)
        )
        val histSystem = ActorSystem(
          GameManager(
            persistProbe.ref,
            new InMemRepo,
            noOpLobbyRepo,
            moveRepo = new InMemMoveRepo(Map(roomId -> moves))
          ),
          "GameRoutesHistorySystem"
        )
        val histRoutes = new GameRoutes(GameType.TicTacToe, histSystem).routes

        Get(s"/tictactoe/$roomId/history") ~> histRoutes ~> check {
          status shouldBe StatusCodes.OK
          val history = responseAs[GameManager.MoveHistory]
          history.roomId shouldBe roomId
          history.moves.map(_.seq) shouldBe List(0, 1)
          history.moves.map(_.move) shouldBe moves.map(_.move)
        }
      }

      "return an empty move history for a game with no moves" in {
        val roomId = UUID.randomUUID()
        Get(s"/tictactoe/$roomId/history") ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.MoveHistory].moves shouldBe empty
        }
      }

      "return the recorded chat history" in {
        val roomId = UUID.randomUUID()
        val messages = List(
          PlayerEvent.ChatMessage(roomId, aliceId, "alice", "hi", Instant.EPOCH),
          PlayerEvent.ChatMessage(roomId, bobId, "bob", "hey", Instant.EPOCH)
        )
        val chatSystem = ActorSystem(
          GameManager(
            persistProbe.ref,
            new InMemRepo,
            noOpLobbyRepo,
            chatRepo = new InMemChatRepo(Map(roomId -> messages))
          ),
          "GameRoutesChatSystem"
        )
        val chatRoutes = new GameRoutes(GameType.TicTacToe, chatSystem).routes

        Get(s"/tictactoe/$roomId/chat") ~> chatRoutes ~> check {
          status shouldBe StatusCodes.OK
          val history = responseAs[GameManager.ChatHistory]
          history.roomId shouldBe roomId
          history.messages.map(_.text) shouldBe List("hi", "hey")
          history.messages.map(_.senderName) shouldBe List("alice", "bob")
        }
      }

      "return an empty chat history for a game with no messages" in {
        val roomId = UUID.randomUUID()
        Get(s"/tictactoe/$roomId/chat") ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.ChatHistory].messages shouldBe empty
        }
      }

      "return 400 for invalid game ID on move" in {
        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"row":0,"col":0}""")

        Post("/tictactoe/invalid-game-id/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
        }
      }

      "return 400 for invalid JSON structure on move" in {
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val invalidJson = """{ "x": 0, "y": 1 }"""
        val entity = HttpEntity(ContentTypes.`application/json`, invalidJson)

        Post(s"/tictactoe/$roomId/move", entity).withHeaders(aliceHeader) ~> routes ~> check {
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

          case GameManager.UnsubscribePlayerFromGame(_, _, replyTo) =>
            replyTo ! GameManager.Ready // triggers DELETE /subscribe fallback
            Behaviors.same

          case GameManager.GetMoveHistory(_, replyTo) =>
            replyTo ! GameManager.Ready // triggers /history fallback
            Behaviors.same

          case GameManager.GetChatHistory(_, replyTo) =>
            replyTo ! GameManager.Ready // triggers /chat fallback
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
          ("POST /subscribe", Post(s"/tictactoe/$fakeId/subscribe").withHeaders(aliceHeader)),
          ("DELETE /subscribe", Delete(s"/tictactoe/$fakeId/subscribe").withHeaders(aliceHeader)),
          ("GET /history", Get(s"/tictactoe/$fakeId/history")),
          ("GET /chat", Get(s"/tictactoe/$fakeId/chat"))
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
              case GameManager.GetMoveHistory(_, replyTo) =>
                replyTo ! response
                Behaviors.same
              case GameManager.GetChatHistory(_, replyTo) =>
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
        // /history maps a storage ErrorResponse to 500 as well
        Get(s"/tictactoe/$fakeId/history") ~> errorRoutes ~> check {
          status shouldBe StatusCodes.InternalServerError
          responseAs[String] should include("boom")
        }
        // /chat maps a storage ErrorResponse to 500 as well
        Get(s"/tictactoe/$fakeId/chat") ~> errorRoutes ~> check {
          status shouldBe StatusCodes.InternalServerError
          responseAs[String] should include("boom")
        }
      }

      "return 200 when subscribing to an active game with a WebSocket connection" in {
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }
        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val wsProbe = testKit.createTestProbe[PlayerActor.SessionOutput]()
        val aliceRef = Await.result(
          typedSystem.ask[ActorRef[PlayerActor.Command]](GameManager.RegisterPlayer(alicePlayer, wsProbe.ref, _)),
          3.seconds
        )

        Post(s"/tictactoe/$roomId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.SubscribeAcknowledged].roomId shouldBe roomId
        }

        typedSystem ! GameManager.PlayerDisconnected(alicePlayer.id, aliceRef)
      }

      "return 200 (idempotent) when unsubscribing from a game" in {
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }
        // DELETE is idempotent — it succeeds even with no prior subscription and no active game actor
        Delete(s"/tictactoe/$roomId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.UnsubscribeAcknowledged].roomId shouldBe roomId
        }
      }

      "return 401 when unsubscribing from a game without authentication" in
        Delete(s"/tictactoe/${UUID.randomUUID()}/subscribe") ~> routes ~> check {
          status shouldBe StatusCodes.Unauthorized
        }

      "return 400 when subscribing to a game without an active WebSocket connection" in {
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }
        Post("/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check(())
        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check(())

        // Alice has no WebSocket connection registered, so the subscribe will fail
        Post(s"/tictactoe/$roomId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("not connected")
        }
      }

      "return 401 when subscribing to a game without authentication" in {
        val roomId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/tictactoe/$roomId/subscribe") ~> routes ~> check {
          status shouldBe StatusCodes.Unauthorized
        }
      }
    }

    "handling ConnectFour" should {
      "submit a move to a valid game" in {
        val roomId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val moveEntity = HttpEntity(ContentTypes.`application/json`, """{"col":3}""")

        Post(s"/connectfour/$roomId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
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
        val roomId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Get(s"/connectfour/$roomId/status") ~> routes ~> check {
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
        val roomId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val invalidJson = """{ "row": 0, "column": 3 }"""
        val entity = HttpEntity(ContentTypes.`application/json`, invalidJson)

        Post(s"/connectfour/$roomId/move", entity).withHeaders(aliceHeader) ~> routes ~> check {
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
        val roomId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }
        Post(s"/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }
        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
        }

        val wsProbe = testKit.createTestProbe[PlayerActor.SessionOutput]()
        val aliceRef = Await.result(
          typedSystem.ask[ActorRef[PlayerActor.Command]](GameManager.RegisterPlayer(alicePlayer, wsProbe.ref, _)),
          3.seconds
        )

        Post(s"/connectfour/$roomId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          responseAs[GameManager.SubscribeAcknowledged].roomId shouldBe roomId
        }

        typedSystem ! GameManager.PlayerDisconnected(alicePlayer.id, aliceRef)
      }

      "return 400 when subscribing to a game without an active WebSocket connection" in {
        val roomId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }
        Post("/lobby/$roomId/join").withHeaders(bobHeader) ~> routes ~> check(())
        Post(s"/lobby/$roomId/start").withHeaders(aliceHeader) ~> routes ~> check(())

        Post(s"/connectfour/$roomId/subscribe").withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.BadRequest
          responseAs[String] should include("not connected")
        }
      }

      "return 401 when subscribing to a game without authentication" in {
        val roomId = Post("/lobby/create/connectfour").withHeaders(aliceHeader) ~> routes ~> check {
          responseAs[GameManager.LobbyCreated].roomId
        }

        Post(s"/connectfour/$roomId/subscribe") ~> routes ~> check {
          status shouldBe StatusCodes.Unauthorized
        }
      }
    }
  }
}
