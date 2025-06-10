package com.andy327.server.http.routes

import scala.concurrent.duration._

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.{ContentTypes, StatusCodes}
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec
import spray.json._

import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.http.json.{TicTacToeMove, TicTacToeState}

class TicTacToeRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  private val testKit = ActorTestKit()
  implicit val timeout: Timeout = Timeout(3.seconds)

  private val persistProbe = testKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo), "TicTacToeRoutesSpecSystem")

  private val routes = new TicTacToeRoutes(typedSystem).routes

  "TicTacToeRoutes" should {
    "create a new TicTacToe game via POST /tictactoe?playerX=alice&playerO=bob" in
      Post("/tictactoe?playerX=alice&playerO=bob") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        responseAs[String].nonEmpty shouldBe true // gameId as plain text
      }

    "make a valid move via POST /tictactoe/{gameId}/move" in
      // Create game first
      Post("/tictactoe?playerX=alice&playerO=bob") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        val gameId = responseAs[String]

        val move = TicTacToeMove("alice", 0, 0)
        val moveJson = move.toJson.compactPrint

        Post(s"/tictactoe/$gameId/move").withEntity(ContentTypes.`application/json`, moveJson) ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
          gameState.board(0)(0) shouldBe "X"
        }
      }

    "reject a move from unknown player" in
      Post("/tictactoe?playerX=alice&playerO=bob") ~> routes ~> check {
        val gameId = responseAs[String]

        val badMove = TicTacToeMove("eve", 1, 1)
        val badMoveJson = badMove.toJson.compactPrint

        Post(s"/tictactoe/$gameId/move").withEntity(ContentTypes.`application/json`, badMoveJson) ~> routes ~> check {
          status shouldBe StatusCodes.NotFound
          responseAs[String].toLowerCase should include("not part of this game")
        }
      }

    "get current state via GET /tictactoe/{gameId}/status" in
      Post("/tictactoe?playerX=alice&playerO=bob") ~> routes ~> check {
        val gameId = responseAs[String]

        Get(s"/tictactoe/$gameId/status") ~> routes ~> check {
          status shouldBe StatusCodes.OK
          val gameState = responseAs[String].parseJson.convertTo[TicTacToeState]
          gameState.currentPlayer shouldBe "X"
        }
      }

    "return 404 for invalid game ID on move" in {
      val move = TicTacToeMove("alice", 0, 0)
      val moveJson = move.toJson.compactPrint

      Post("/tictactoe/invalid-game-id/move").withEntity(ContentTypes.`application/json`, moveJson) ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
    }

    "return 404 for invalid game ID on status" in
      Get("/tictactoe/invalid-game-id/status") ~> routes ~> check {
        status shouldBe StatusCodes.NotFound
      }
  }
}
