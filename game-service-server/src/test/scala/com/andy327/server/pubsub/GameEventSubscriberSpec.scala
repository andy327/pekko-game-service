package com.andy327.server.pubsub

import java.util.UUID

import scala.concurrent.duration._

import cats.effect.IO
import cats.effect.std.Queue
import cats.effect.unsafe.IORuntime

import fs2.Stream
import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.http.scaladsl.model.ws.TextMessage
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.server.actors.core.PlayerActor
import com.andy327.server.lobby.Player

class GameEventSubscriberSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  implicit val runtime: IORuntime = IORuntime.global

  "GameEventSubscriber" should {
    "route an incoming Redis message to a registered player actor" in {
      val gameId = UUID.randomUUID()
      val json = """{"type":"GameStateUpdated"}"""

      // Emit one event then block so run doesn't terminate
      val stream = Stream.emit((s"game-events:$gameId", json)).covary[IO] ++ Stream.never[IO]
      val subscriber = GameEventSubscriber.create(stream).unsafeRunSync()

      val wsProbe = createTestProbe[PlayerActor.WsOutput]()
      val playerRef = spawn(PlayerActor(Player("alice"), wsProbe.ref))
      subscriber.registerPlayer(gameId, playerRef).unsafeRunSync()

      val fiber = subscriber.run.start.unsafeRunSync()
      wsProbe.expectMessageType[PlayerActor.WsMessage].message.asInstanceOf[TextMessage.Strict].text shouldBe json
      fiber.cancel.unsafeRunSync()
    }

    "route an event to all players registered for the same game" in {
      val gameId = UUID.randomUUID()
      val json = """{"type":"GameStateUpdated"}"""

      val stream = Stream.emit((s"game-events:$gameId", json)).covary[IO] ++ Stream.never[IO]
      val subscriber = GameEventSubscriber.create(stream).unsafeRunSync()

      val wsProbe1 = createTestProbe[PlayerActor.WsOutput]()
      val wsProbe2 = createTestProbe[PlayerActor.WsOutput]()
      val playerRef1 = spawn(PlayerActor(Player("alice"), wsProbe1.ref))
      val playerRef2 = spawn(PlayerActor(Player("bob"), wsProbe2.ref))
      subscriber.registerPlayer(gameId, playerRef1).unsafeRunSync()
      subscriber.registerPlayer(gameId, playerRef2).unsafeRunSync()

      val fiber = subscriber.run.start.unsafeRunSync()
      wsProbe1.expectMessageType[PlayerActor.WsMessage].message.asInstanceOf[TextMessage.Strict].text shouldBe json
      wsProbe2.expectMessageType[PlayerActor.WsMessage].message.asInstanceOf[TextMessage.Strict].text shouldBe json
      fiber.cancel.unsafeRunSync()
    }

    "not route events after unregisterGame is called" in {
      val gameId = UUID.randomUUID()
      val json = """{"type":"GameStateUpdated"}"""

      val queue = Queue.unbounded[IO, (String, String)].unsafeRunSync()
      val subscriber = GameEventSubscriber.create(Stream.fromQueueUnterminated(queue)).unsafeRunSync()

      val wsProbe = createTestProbe[PlayerActor.WsOutput]()
      val playerRef = spawn(PlayerActor(Player("alice"), wsProbe.ref))
      subscriber.registerPlayer(gameId, playerRef).unsafeRunSync()
      subscriber.unregisterGame(gameId).unsafeRunSync()

      val fiber = subscriber.run.start.unsafeRunSync()
      queue.offer((s"game-events:$gameId", json)).unsafeRunSync()

      wsProbe.expectNoMessage(100.millis)
      fiber.cancel.unsafeRunSync()
    }

    "not route events for games with no registered players" in {
      val gameId = UUID.randomUUID()
      val otherGameId = UUID.randomUUID()
      val json = """{"type":"GameStateUpdated"}"""

      // Message arrives for gameId, but the only registered player is for otherGameId
      val stream = Stream.emit((s"game-events:$gameId", json)).covary[IO] ++ Stream.never[IO]
      val subscriber = GameEventSubscriber.create(stream).unsafeRunSync()

      val wsProbe = createTestProbe[PlayerActor.WsOutput]()
      val playerRef = spawn(PlayerActor(Player("alice"), wsProbe.ref))
      subscriber.registerPlayer(otherGameId, playerRef).unsafeRunSync()

      val fiber = subscriber.run.start.unsafeRunSync()
      wsProbe.expectNoMessage(100.millis)
      fiber.cancel.unsafeRunSync()
    }

    "ignore events whose channel does not contain a valid game UUID" in {
      val json = """{"type":"GameStateUpdated"}"""
      val badChannel = "game-events:not-a-valid-uuid"

      val stream = Stream.emit((badChannel, json)).covary[IO] ++ Stream.never[IO]
      val subscriber = GameEventSubscriber.create(stream).unsafeRunSync()

      val wsProbe = createTestProbe[PlayerActor.WsOutput]()
      val playerRef = spawn(PlayerActor(Player("alice"), wsProbe.ref))
      subscriber.registerPlayer(UUID.randomUUID(), playerRef).unsafeRunSync()

      val fiber = subscriber.run.start.unsafeRunSync()
      wsProbe.expectNoMessage(100.millis)
      fiber.cancel.unsafeRunSync()
    }
  }
}
