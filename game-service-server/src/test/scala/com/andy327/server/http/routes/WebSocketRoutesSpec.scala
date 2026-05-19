package com.andy327.server.http.routes

import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorSystem
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.server.actors.core.{GameManager, InMemRepo}
import com.andy327.server.actors.persistence.PersistenceProtocol
import com.andy327.server.lobby.Player
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class WebSocketRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  implicit val runtime: IORuntime = IORuntime.global

  private val typedKit = ActorTestKit()
  private val persistProbe = typedKit.createTestProbe[PersistenceProtocol.Command]()
  private val gameRepo = new InMemRepo
  private val typedSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, gameRepo), "WebSocketRoutesSpecSystem")

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
  }
}
