package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.{TicTacToeMoveRequest, TicTacToeState}
import com.andy327.server.lobby.Player
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class GameRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val timeout: Timeout = Timeout(3.seconds)

  private val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo), "GameRoutesSpecSystem")

  private val routes = concat(
    new LobbyRoutes(typedSystem).routes,
    new GameRoutes(GameType.TicTacToe, typedSystem).routes
  )

  val (aliceId, bobId, carlId) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

  val alicePlayer: Player = Player(aliceId, "alice")
  val bobPlayer: Player = Player(bobId, "bob")
  val carlPlayer: Player = Player(carlId, "carl")

  val aliceHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(alicePlayer)}")
  val bobHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(bobPlayer)}")
  val carlHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(carlPlayer)}")

  "GameRoutes" when {
    "initialized with a gameType missing from the registry" should {
      "throw an IllegalArgumentException with a helpful message" in {
        val dummySystem = ActorSystem(Behaviors.empty[GameManager.Command], "MissingModuleTestSystem")
        val fakeProvider: GameModuleProvider = _ => None

        val exception = intercept[IllegalArgumentException] {
          new GameRoutes(GameType.TicTacToe, dummySystem, fakeProvider)
        }

        exception.getMessage should include("No module registered for TicTacToe")
      }
    }

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

        val move = TicTacToeMoveRequest(0, 0)
        val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)

        Post(s"/tictactoe/$gameId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
          gameState.board(0)(0) shouldBe "X"
        }
      }

      "fail to move in a nonexistent game" in {
        val fakeId = UUID.randomUUID()
        val move = TicTacToeMoveRequest(0, 0)
        val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)
        Post(s"/tictactoe/$fakeId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
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
          val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
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
        val move = TicTacToeMoveRequest(0, 0)
        val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)

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

          case _ => Behaviors.same
        }

        val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseSystem")
        val errorRoutes = new GameRoutes(GameType.TicTacToe, dummySystem).routes

        val move = TicTacToeMoveRequest(0, 0)
        val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)

        val requests = Table(
          ("description", "request"),
          ("POST /move", Post(s"/tictactoe/$fakeId/move", moveEntity).withHeaders(aliceHeader)),
          ("GET /status", Get(s"/tictactoe/$fakeId/status"))
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
    }
  }
}
