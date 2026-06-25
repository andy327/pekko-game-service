package com.andy327.server.http.routes

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{GameId, GameType}
import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.lobby.{LobbyMetadata, LobbyRepository, Player}
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class WebSocketRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  implicit val runtime: IORuntime = IORuntime.global

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(gameId: GameId): IO[Unit] = IO.unit
    override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
  }

  private val typedKit = ActorTestKit()
  private val persistProbe = typedKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo, noOpLobbyRepo), "WebSocketRoutesSpecSystem")

  private val routes = new WebSocketRoutes(typedSystem).routes

  "WebSocketRoutes" should {
    "reject a connection with no Authorization header" in {
      val wsClient = WSProbe()
      WS("/ws", wsClient.flow) ~> routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }

    "reject a connection with an invalid token" in {
      val wsClient = WSProbe()
      WS("/ws", wsClient.flow) ~> addHeader(RawHeader("Authorization", "Bearer not-a-real-token")) ~>
      routes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }

    "upgrade the connection to WebSocket with a valid token" in {
      val alice = Player("alice")
      val token = createTestToken(alice)
      val wsClient = WSProbe()

      WS("/ws", wsClient.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~>
      routes ~> check {
        isWebSocketUpgrade shouldBe true
      }
    }

    "push server events through the socket and close it when the player reconnects" in {
      val alice = Player("alice")
      val token = createTestToken(alice)
      val responseProbe = typedKit.createTestProbe[GameManager.GameResponse]()

      // a lobby alice can subscribe to once her WebSocket session is registered
      typedSystem ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val gameId = responseProbe.expectMessageType[GameManager.LobbyCreated].gameId

      val wsClient = WSProbe()
      WS("/ws", wsClient.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        isWebSocketUpgrade shouldBe true

        // registration happens asynchronously after connect, so retry the subscribe until it lands
        responseProbe.awaitAssert {
          typedSystem ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
          responseProbe.receiveMessage() shouldBe a[GameManager.SubscribeAcknowledged]
        }

        // the subscribe pushes the current lobby state: LobbyManager -> PlayerActor -> wsOut -> client frame
        wsClient.expectMessage().asTextMessage.getStrictText should include("LobbyUpdated")

        // alice reconnects: the old session's PlayerActor emits SessionComplete, closing this socket server-side
        val wsClient2 = WSProbe()
        WS("/ws", wsClient2.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
          isWebSocketUpgrade shouldBe true
        }
        wsClient.expectCompletion()
      }
    }

    "drop a malformed inbound frame and still route a valid ChatSend back to the match's subscribers" in {
      val alice = Player("alice")
      val token = createTestToken(alice)
      val responseProbe = typedKit.createTestProbe[GameManager.GameResponse]()

      typedSystem ! GameManager.CreateLobby(GameType.TicTacToe, alice, responseProbe.ref)
      val gameId = responseProbe.expectMessageType[GameManager.LobbyCreated].gameId

      val wsClient = WSProbe()
      WS("/ws", wsClient.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~> routes ~> check {
        isWebSocketUpgrade shouldBe true

        // subscribe alice to the lobby once her session is registered (registration is async)
        responseProbe.awaitAssert {
          typedSystem ! GameManager.SubscribePlayerToLobby(gameId, alice.id, responseProbe.ref)
          responseProbe.receiveMessage() shouldBe a[GameManager.SubscribeAcknowledged]
        }
        wsClient.expectMessage().asTextMessage.getStrictText should include("LobbyUpdated") // initial state

        // a malformed frame is logged and dropped without tearing down the connection
        wsClient.sendMessage("not even json")

        // the next valid chat frame is still routed: inbound decode -> SendChat -> lobby fan-out -> back to her socket
        wsClient.sendMessage(s"""{"type":"ChatSend","gameId":"$gameId","text":"hello all"}""")
        val frame = wsClient.expectMessage().asTextMessage.getStrictText
        frame should include("ChatMessage")
        frame should include("hello all")
      }
    }
  }
}
