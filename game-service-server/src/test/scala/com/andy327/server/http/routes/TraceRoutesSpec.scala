package com.andy327.server.http.routes

import java.time.Instant

import scala.concurrent.Await
import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.headers.RawHeader
import org.apache.pekko.http.scaladsl.testkit.{ScalatestRouteTest, WSProbe}
import org.apache.pekko.util.Timeout
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.core.{GameManager, InMemRepo}
import com.andy327.actor.lobby.{LobbyMetadata, LobbyRepository, Player}
import com.andy327.actor.persistence.PersistenceProtocol
import com.andy327.actor.tracing.{TraceCollector, TraceEvent, TracingConfig}
import com.andy327.model.core.RoomId
import com.andy327.server.testutil.AuthTestHelper.createTestToken

class TraceRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {
  implicit val runtime: IORuntime = IORuntime.global
  implicit val askTimeout: Timeout = Timeout(5.seconds)

  private val noOpLobbyRepo: LobbyRepository = new LobbyRepository {
    override def saveLobby(metadata: LobbyMetadata): IO[Unit] = IO.unit
    override def deleteLobby(roomId: RoomId): IO[Unit] = IO.unit
    override def loadAllLobbies(): IO[List[LobbyMetadata]] = IO.pure(Nil)
  }

  private val typedKit = ActorTestKit()
  private val persistProbe = typedKit.createTestProbe[PersistenceProtocol.Command]()

  private val disabledSystem: ActorSystem[GameManager.Command] =
    ActorSystem(GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo), "TraceRoutesSpecDisabledSystem")
  private val disabledRoutes = new TraceRoutes(disabledSystem).routes

  private val enabledTracingConfig = TracingConfig(enabled = true, sampleRate = 1.0, bufferSize = 100)
  private val enabledSystem: ActorSystem[GameManager.Command] = ActorSystem(
    GameManager(persistProbe.ref, new InMemRepo, noOpLobbyRepo, tracingConfig = enabledTracingConfig),
    "TraceRoutesSpecEnabledSystem"
  )
  private val enabledRoutes = new TraceRoutes(enabledSystem).routes
  implicit private val scheduler: Scheduler = enabledSystem.scheduler

  "TraceRoutes" should {
    "reject a connection with no Authorization header" in {
      val wsClient = WSProbe()
      WS("/ws/trace", wsClient.flow) ~> disabledRoutes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }

    "reject a connection with an invalid token" in {
      val wsClient = WSProbe()
      WS("/ws/trace", wsClient.flow) ~> addHeader(RawHeader("Authorization", "Bearer not-a-real-token")) ~>
      disabledRoutes ~> check {
        status shouldBe StatusCodes.Unauthorized
      }
    }

    "reject an authenticated connection with 503 when tracing is disabled" in {
      val alice = Player("alice")
      val token = createTestToken(alice)
      val wsClient = WSProbe()

      WS("/ws/trace", wsClient.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~>
      disabledRoutes ~> check {
        status shouldBe StatusCodes.ServiceUnavailable
      }
    }

    "upgrade the connection to WebSocket with a valid token when tracing is enabled" in {
      val alice = Player("alice")
      val token = createTestToken(alice)
      val wsClient = WSProbe()

      WS("/ws/trace", wsClient.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~>
      enabledRoutes ~> check {
        isWebSocketUpgrade shouldBe true
      }
    }

    "push a TraceEvent recorded on the collector through the socket as a JSON frame" in {
      val alice = Player("alice")
      val token = createTestToken(alice)
      val wsClient = WSProbe()

      WS("/ws/trace", wsClient.flow) ~> addHeader(RawHeader("Authorization", s"Bearer $token")) ~>
      enabledRoutes ~> check {
        isWebSocketUpgrade shouldBe true

        val collectorFuture = enabledSystem ? GameManager.GetTraceCollector
        val collector = Await.result(collectorFuture, 3.seconds).get

        val event = TraceEvent(from = None, to = "actor-1", messageType = "Ping", timestamp = Instant.now())
        collector ! TraceCollector.Record(event)

        // the collector replays its pre-existing buffer on subscribe (e.g. RestoreLobbies from system startup,
        // since the shared enabledSystem's own actors are traced too), so the synthetic event isn't necessarily first
        val frame = LazyList.continually(wsClient.expectMessage().asTextMessage.getStrictText).find(_.contains("Ping"))
        frame.get should include("actor-1")
      }
    }
  }
}
