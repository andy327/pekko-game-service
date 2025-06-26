package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.{TicTacToeMove, TicTacToeState}
import com.andy327.server.lobby.{GameMetadata, Player}
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class TicTacToeRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val timeout: Timeout = Timeout(3.seconds)

  private val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo), "TicTacToeRoutesSpecSystem")

  private val routes = new TicTacToeRoutes(typedSystem).routes

  val (aliceId, bobId, carlId) = (UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID())

  val alicePlayer: Player = Player(aliceId, "alice")
  val bobPlayer: Player = Player(bobId, "bob")
  val carlPlayer: Player = Player(carlId, "carl")

  val aliceHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(alicePlayer)}")
  val bobHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(bobPlayer)}")
  val carlHeader: RawHeader = RawHeader("Authorization", s"Bearer ${createTestToken(carlPlayer)}")

  "TicTacToeRoutes" should {
    "create a new lobby" in
      Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val GameManager.LobbyCreated(gameId, host) = responseAs[GameManager.LobbyCreated]
        gameId.length should be > 0
        host.name shouldBe "alice"
        host.id shouldBe aliceId
      }

    "join a lobby with a second player" in {
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/tictactoe/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val GameManager.LobbyJoined(_, metadata, joinedPlayer) = responseAs[GameManager.LobbyJoined]
        metadata.players.values.toSet should contain only (alicePlayer, joinedPlayer)
      }
    }

    "not allow too many players to join a lobby" in {
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/tictactoe/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/tictactoe/lobby/$gameId/join").withHeaders(carlHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "start a game after a lobby has enough players" in {
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/tictactoe/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/tictactoe/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe gameId
      }
    }

    "reject starting a game with not enough players" in {
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/tictactoe/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Only host can start, and game must be ready to start")
      }
    }

    "list all lobbies" in {
      // Create a new lobby
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      // Check for lobby in list of active lobbies
      Get("/tictactoe/lobbies") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[List[GameMetadata]].map(_.gameId) should contain(gameId)
      }
    }

    "submit a move to a valid game" in {
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/tictactoe/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/tictactoe/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      val move = TicTacToeMove(0, 0)
      val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)

      Post(s"/tictactoe/$gameId/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
        gameState.board(0)(0) shouldBe "X"
      }
    }

    "fail to move in a nonexistent game" in {
      val move = TicTacToeMove(0, 0)
      val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)
      Post("/tictactoe/nonexistent/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "fetch game status" in {
      val gameId = Post("/tictactoe/lobby").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/tictactoe/lobby/$gameId/join").withHeaders(bobHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Post(s"/tictactoe/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      Get(s"/tictactoe/$gameId/status") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
        gameState.currentPlayer shouldBe "X"
      }
    }

    "return 404 for status of unknown game" in
      Get("/tictactoe/nonexistent/status") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }

    "return 404 for invalid game ID on move" in {
      val move = TicTacToeMove(0, 0)
      val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)

      Post("/tictactoe/invalid-game-id/move", moveEntity).withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 404 for invalid game ID on status" in
      Get("/tictactoe/invalid-game-id/status") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }

    "return 500 for unexpected responses" in {
      val dummy = Player("dummy")
      val dummyState = TicTacToeState(Vector(), "X", None, draw = false)
      val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
        case GameManager.CreateLobby(_, _, replyTo) =>
          replyTo ! GameManager.GameStatus(dummyState) // triggers /lobby fallback
          Behaviors.same

        case GameManager.JoinLobby(_, _, replyTo) =>
          replyTo ! GameManager.LobbyCreated("unexpected", dummy) // triggers /join fallback
          Behaviors.same

        case GameManager.StartGame(_, _, replyTo) =>
          replyTo ! GameManager.LobbiesListed(Nil) // triggers /start fallback
          Behaviors.same

        case GameManager.ListLobbies(replyTo) =>
          replyTo ! GameManager.GameStarted("unexpected") // triggers /lobbies fallback
          Behaviors.same

        case GameManager.ForwardToGame(_, _, Some(replyTo)) =>
          replyTo ! GameManager.LobbyCreated("unexpected", dummy) // triggers /move + /status fallback
          Behaviors.same

        case _ => Behaviors.same
      }

      val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseSystem")
      val errorRoutes = new TicTacToeRoutes(dummySystem).routes

      val move = TicTacToeMove(0, 0)
      val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)

      val requests = Table(
        ("description", "request"),
        ("POST /lobby", Post("/tictactoe/lobby").withHeaders(aliceHeader)),
        ("POST /lobby/join", Post("/tictactoe/lobby/fake-id/join").withHeaders(aliceHeader)),
        ("POST /lobby/start", Post("/tictactoe/lobby/fake-id/start").withHeaders(aliceHeader)),
        ("GET /lobbies", Get("/tictactoe/lobbies")),
        ("POST /move", Post("/tictactoe/fake-id/move", moveEntity).withHeaders(aliceHeader)),
        ("GET /status", Get("/tictactoe/fake-id/status"))
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
