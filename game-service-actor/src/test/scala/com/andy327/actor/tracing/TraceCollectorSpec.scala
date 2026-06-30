package com.andy327.actor.tracing

import java.time.Instant

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TraceCollectorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  private def event(n: Int): TraceEvent =
    TraceEvent(from = None, to = s"actor-$n", messageType = "Ping", timestamp = Instant.ofEpochMilli(n.toLong))

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
  }
}
