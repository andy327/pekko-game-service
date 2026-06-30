package com.andy327.actor.tracing

import scala.collection.mutable.ListBuffer

import org.apache.pekko.actor.testkit.typed.scaladsl.ActorTestKit
import org.apache.pekko.actor.typed.ActorRef
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TracingInterceptorSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  // A minimal command type for the wrapped behavior under test.
  sealed private trait Cmd
  private case class Ping(replyTo: ActorRef[String]) extends Cmd
  private case class Pong(replyTo: ActorRef[String]) extends Cmd

  // A behavior that replies "pong" to Ping and "ping" to Pong.
  private val echoBehavior: Behaviors.Receive[Cmd] =
    Behaviors.receiveMessage {
      case Ping(r) => r ! "pong"; Behaviors.same
      case Pong(r) => r ! "ping"; Behaviors.same
    }

  private val enabled = TracingConfig(enabled = true, sampleRate = 1.0, bufferSize = 1000)
  private val disabled = TracingConfig(enabled = false, sampleRate = 1.0, bufferSize = 1000)
  private val zeroRate = TracingConfig(enabled = true, sampleRate = 0.0, bufferSize = 1000)

  "TracingInterceptor.wrap" when {

    "config is disabled" should {
      "pass messages through to the wrapped behavior unchanged" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, disabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")

        actor ! Pong(probe.ref)
        probe.expectMessage("ping")
      }

      "never emit any TraceEvents" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, disabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")
        actor ! Pong(probe.ref)
        probe.expectMessage("ping")

        emitted shouldBe empty
      }
    }

    "config is enabled with sampleRate = 1.0" should {
      "pass every message through to the wrapped behavior" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, enabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")
        actor ! Pong(probe.ref)
        probe.expectMessage("ping")
      }

      "emit one TraceEvent per message" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, enabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")
        actor ! Pong(probe.ref)
        probe.expectMessage("ping")

        emitted should have size 2
      }

      "populate messageType from the concrete message class" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, enabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")

        emitted.head.messageType shouldBe "Ping"
      }

      "set to to the receiving actor's path" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, enabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")

        emitted.head.to should include(actor.path.name)
      }

      "set from to None (typed Pekko does not expose the sender)" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, enabled, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")

        emitted.head.from shouldBe None
      }
    }

    "config is enabled with sampleRate = 0.0" should {
      "still pass every message through to the wrapped behavior" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, zeroRate, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")
      }

      "never emit any TraceEvents" in {
        val emitted = ListBuffer.empty[TraceEvent]
        val probe = createTestProbe[String]()
        val actor = spawn(TracingInterceptor.wrap[Cmd](echoBehavior, zeroRate, emitted += _))

        actor ! Ping(probe.ref)
        probe.expectMessage("pong")
        actor ! Pong(probe.ref)
        probe.expectMessage("ping")

        emitted shouldBe empty
      }
    }
  }
}
