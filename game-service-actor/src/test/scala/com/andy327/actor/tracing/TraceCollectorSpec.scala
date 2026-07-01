package com.andy327.actor.tracing

import java.time.Instant

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TraceCollectorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  private def event(n: Int): TraceEvent =
    TraceEvent(to = s"actor-$n", messageType = "Ping", timestamp = Instant.ofEpochMilli(n.toLong))

  "TraceCollector" should {
    "reply with an empty buffer before any events are recorded" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      val probe = createTestProbe[Vector[TraceEvent]]()

      collector ! TraceCollector.GetRecent(probe.ref)

      probe.expectMessage(Vector.empty)
    }

    "return recorded events oldest first" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      val probe = createTestProbe[Vector[TraceEvent]]()

      collector ! TraceCollector.Record(event(1))
      collector ! TraceCollector.Record(event(2))
      collector ! TraceCollector.Record(event(3))
      collector ! TraceCollector.GetRecent(probe.ref)

      probe.expectMessage(Vector(event(1), event(2), event(3)))
    }

    "drop the oldest event once the buffer exceeds its capacity" in {
      val collector = spawn(TraceCollector(bufferSize = 2))
      val probe = createTestProbe[Vector[TraceEvent]]()

      collector ! TraceCollector.Record(event(1))
      collector ! TraceCollector.Record(event(2))
      collector ! TraceCollector.Record(event(3))
      collector ! TraceCollector.GetRecent(probe.ref)

      probe.expectMessage(Vector(event(2), event(3)))
    }

    "never exceed bufferSize even after many more events than capacity" in {
      val collector = spawn(TraceCollector(bufferSize = 3))
      val probe = createTestProbe[Vector[TraceEvent]]()

      (1 to 10).foreach(n => collector ! TraceCollector.Record(event(n)))
      collector ! TraceCollector.GetRecent(probe.ref)

      probe.expectMessage(Vector(event(8), event(9), event(10)))
    }

    "replay the existing buffer to a new subscriber immediately, oldest first" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      collector ! TraceCollector.Record(event(1))
      collector ! TraceCollector.Record(event(2))

      val subscriber = createTestProbe[TraceEvent]()
      collector ! TraceCollector.Subscribe(subscriber.ref)

      subscriber.expectMessage(event(1))
      subscriber.expectMessage(event(2))
    }

    "forward a live Record to a subscriber after it subscribes" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      val subscriber = createTestProbe[TraceEvent]()
      collector ! TraceCollector.Subscribe(subscriber.ref)

      collector ! TraceCollector.Record(event(1))

      subscriber.expectMessage(event(1))
    }

    "forward a live Record to every current subscriber" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      val subscriberA = createTestProbe[TraceEvent]()
      val subscriberB = createTestProbe[TraceEvent]()
      collector ! TraceCollector.Subscribe(subscriberA.ref)
      collector ! TraceCollector.Subscribe(subscriberB.ref)

      collector ! TraceCollector.Record(event(1))

      subscriberA.expectMessage(event(1))
      subscriberB.expectMessage(event(1))
    }

    "stop forwarding events to a subscriber once it unsubscribes" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      val subscriber = createTestProbe[TraceEvent]()
      collector ! TraceCollector.Subscribe(subscriber.ref)
      collector ! TraceCollector.Unsubscribe(subscriber.ref)

      collector ! TraceCollector.Record(event(1))

      subscriber.expectNoMessage()
    }

    "drop a subscriber that terminates without crashing on the death-watch" in {
      val collector = spawn(TraceCollector(bufferSize = 10))
      // a real actor we can stop to trigger a Terminated signal in the watching TraceCollector
      val subscriber = spawn(Behaviors.empty[TraceEvent])
      collector ! TraceCollector.Subscribe(subscriber)

      testKit.stop(subscriber)

      // without a Terminated handler the watching TraceCollector would crash with a DeathPactException; instead it
      // stays responsive and serves a subsequent command
      val probe = createTestProbe[Vector[TraceEvent]]()
      collector ! TraceCollector.Record(event(1))
      collector ! TraceCollector.GetRecent(probe.ref)

      probe.expectMessage(Vector(event(1)))
    }
  }
}
