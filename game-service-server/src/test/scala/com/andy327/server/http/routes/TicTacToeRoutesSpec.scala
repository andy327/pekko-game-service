package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import com.andy327.model.core.PlayerId
import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.{TicTacToeMove, TicTacToeState}
import com.andy327.server.lobby.{GameMetadata, Player}

class TicTacToeRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val timeout: Timeout = Timeout(3.seconds)

  private val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo), "TicTacToeRoutesSpecSystem")

  private val routes = new TicTacToeRoutes(typedSystem).routes

  "TicTacToeRoutes" should {
    "create a new lobby" in {
      val player = Player(UUID.randomUUID(), "alice")
      val requestEntity = HttpEntity(ContentTypes.`application/json`, player.toJson.compactPrint)
      Post("/tictactoe/lobby", requestEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val GameManager.LobbyCreated(gameId, host) = responseAs[GameManager.LobbyCreated]
        gameId.length should be > 0
        host shouldBe player
      }
    }

    "join a lobby with a second player" in {
      val host = Player(UUID.randomUUID(), "alice")
      val hostEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", hostEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val player2 = Player(UUID.randomUUID(), "bob")
      val joinEntity = HttpEntity(ContentTypes.`application/json`, player2.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/join", joinEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val GameManager.LobbyJoined(_, metadata, _) = responseAs[GameManager.LobbyJoined]
        metadata.players.values.toSet should contain only (host, player2)
      }
    }

    "not allow too many players to join a lobby" in {
      val host = Player(UUID.randomUUID(), "alice")
      val hostEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", hostEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val player2 = Player(UUID.randomUUID(), "bob")
      val join1 = HttpEntity(ContentTypes.`application/json`, player2.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/join", join1) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      val player3 = Player(UUID.randomUUID(), "carl")
      val join2 = HttpEntity(ContentTypes.`application/json`, player3.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/join", join2) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
      }
    }

    "start a game after a lobby has enough players" in {
      val host = Player(UUID.randomUUID(), "alice")
      val hostEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", hostEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val player2 = Player(UUID.randomUUID(), "bob")
      val joinEntity = HttpEntity(ContentTypes.`application/json`, player2.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/join", joinEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      val startEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/start", startEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String] shouldBe gameId
      }
    }

    "reject starting a game with not enough players" in {
      val host = Player(UUID.randomUUID(), "alice")
      val hostEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", hostEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val startEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/start", startEntity) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Only host can start, and game must be ready to start")
      }
    }

    "list all lobbies" in {
      // Create a new lobby
      val player = Player(UUID.randomUUID(), "alice")
      val requestEntity = HttpEntity(ContentTypes.`application/json`, player.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", requestEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      // Check for lobby in list of active lobbies
      Get("/tictactoe/lobbies") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[List[GameMetadata]].map(_.gameId) should contain(gameId)
      }
    }

    "submit a move to a valid game" in {
      val host = Player(UUID.randomUUID(), "alice")
      val hostEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", hostEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val player2 = Player(UUID.randomUUID(), "bob")
      val joinEntity = HttpEntity(ContentTypes.`application/json`, player2.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/join", joinEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      val startEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/start", startEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      val move = TicTacToeMove(host.id, 0, 0)
      val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)
      Post(s"/tictactoe/$gameId/move", moveEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
        gameState.board(0)(0) shouldBe "X"
      }
    }

    "fail to move in a nonexistent game" in {
      val move = TicTacToeMove(UUID.randomUUID(), 0, 0)
      val moveEntity = HttpEntity(ContentTypes.`application/json`, move.toJson.compactPrint)
      Post(s"/tictactoe/nonexistent/move", moveEntity) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "fetch game status" in {
      val host = Player(UUID.randomUUID(), "alice")
      val hostEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      val gameId = Post("/tictactoe/lobby", hostEntity) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      val player2 = Player(UUID.randomUUID(), "bob")
      val joinEntity = HttpEntity(ContentTypes.`application/json`, player2.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/join", joinEntity) ~> routes ~> check {
        status shouldBe StatusCodes.OK
      }

      val startEntity = HttpEntity(ContentTypes.`application/json`, host.toJson.compactPrint)
      Post(s"/tictactoe/lobby/$gameId/start", startEntity) ~> routes ~> check {
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
      val alice: PlayerId = UUID.randomUUID()
      val move = TicTacToeMove(alice, 0, 0)
      val moveJson = move.toJson.compactPrint

      Post("/tictactoe/invalid-game-id/move").withEntity(ContentTypes.`application/json`, moveJson) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 404 for invalid game ID on status" in
      Get("/tictactoe/invalid-game-id/status") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }

    "return 500 for unexpected responses" in {
      val dummyId = Player(UUID.randomUUID(), "dummy")
      val dummyState = TicTacToeState(Vector(), "X", None, draw = false)
      val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
        case GameManager.CreateLobby(_, _, replyTo) =>
          replyTo ! GameManager.GameStatus(dummyState) // triggers /lobby fallback
          Behaviors.same

        case GameManager.JoinLobby(_, _, replyTo) =>
          replyTo ! GameManager.LobbyCreated("unexpected", dummyId) // triggers /join fallback
          Behaviors.same

        case GameManager.StartGame(_, _, replyTo) =>
          replyTo ! GameManager.LobbiesListed(Nil) // triggers /start fallback
          Behaviors.same

        case GameManager.ListLobbies(replyTo) =>
          replyTo ! GameManager.GameStarted("unexpected") // triggers /lobbies fallback
          Behaviors.same

        case GameManager.ForwardToGame(_, _, Some(replyTo)) =>
          replyTo ! GameManager.LobbyCreated("unexpected", dummyId) // triggers /move + /status fallback
          Behaviors.same

        case _ => Behaviors.same
      }

      val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseSystem")
      val errorRoutes = new TicTacToeRoutes(dummySystem).routes

      val playerJson = Player(UUID.randomUUID(), "alice").toJson.compactPrint
      val moveJson = TicTacToeMove(UUID.randomUUID(), 0, 0).toJson.compactPrint

      val requests = Table(
        ("description", "request"),
        ("POST /lobby", Post("/tictactoe/lobby").withEntity(ContentTypes.`application/json`, playerJson)),
        (
          "POST /lobby/join",
          Post("/tictactoe/lobby/fake-id/join").withEntity(ContentTypes.`application/json`, playerJson)
        ),
        (
          "POST /lobby/start",
          Post("/tictactoe/lobby/fake-id/start").withEntity(ContentTypes.`application/json`, playerJson)
        ),
        ("GET /lobbies", Get("/tictactoe/lobbies")),
        ("POST /move", Post("/tictactoe/fake-id/move").withEntity(ContentTypes.`application/json`, moveJson)),
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
