package com.andy327.server.http.routes

import java.util.UUID

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.prop.TableDrivenPropertyChecks._
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.{LobbyMetadata, Player}
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class LobbyRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val timeout: Timeout = Timeout(3.seconds)

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
        gameId.length should be > 0
        host.name shouldBe "alice"
        host.id shouldBe aliceId
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
        status shouldBe StatusCodes.BadRequest
      }
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
        responseAs[String] shouldBe gameId
      }
    }

    "reject starting a game with not enough players" in {
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      Post(s"/lobby/$gameId/start").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Only host can start, and game must be ready to start")
      }
    }

    "list all lobbies" in {
      // Create a new lobby
      val gameId = Post("/lobby/create/tictactoe").withHeaders(aliceHeader) ~> routes ~> check {
        responseAs[GameManager.LobbyCreated].gameId
      }

      // Check for lobby in list of active lobbies
      Get("/lobby/list") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[List[LobbyMetadata]].map(_.gameId) should contain(gameId)
      }
    }

    "return 400 when trying to create a lobby with an invalid game type" in
      Post("/lobby/create/unknowngame").withHeaders(aliceHeader) ~> routes ~> check {
        status shouldBe StatusCodes.BadRequest
        responseAs[String] should include("Invalid game type")
      }

    "return 500 for unexpected responses" in {
      val unexpectedBehavior = Behaviors.receiveMessage[GameManager.Command] {
        case GameManager.CreateLobby(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /create fallback
          Behaviors.same

        case GameManager.JoinLobby(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /join fallback
          Behaviors.same

        case GameManager.StartGame(_, _, replyTo) =>
          replyTo ! GameManager.Ready // triggers /start fallback
          Behaviors.same

        case GameManager.ListLobbies(replyTo) =>
          replyTo ! GameManager.Ready // triggers /list fallback
          Behaviors.same

        case _ => Behaviors.same
      }

      val dummySystem = ActorSystem(unexpectedBehavior, "UnexpectedResponseSystem")
      val errorRoutes = new LobbyRoutes(dummySystem).routes

      val requests = Table(
        ("description", "request"),
        ("POST /lobby/create", Post("/lobby/create/tictactoe").withHeaders(aliceHeader)),
        ("POST /lobby/join", Post("/lobby/fake-id/join").withHeaders(aliceHeader)),
        ("POST /lobby/start", Post("/lobby/fake-id/start").withHeaders(aliceHeader)),
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
  }
}
