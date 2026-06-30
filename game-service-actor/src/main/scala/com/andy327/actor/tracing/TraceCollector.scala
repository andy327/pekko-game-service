package com.andy327.actor.tracing

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

/** Holds the most recent [[TraceEvent]]s in a bounded in-memory buffer, for later retrieval by a debug/visualization
  * endpoint (not yet implemented — see the `feat-trace-debug-viewer` branch).
  *
  * Each [[TracingInterceptor]] installed across the actor system sends its events here via [[Record]]. The buffer
  * retains at most `bufferSize` events, dropping the oldest once full, so memory use stays bounded regardless of how
  * long the system runs or how chatty tracing is.
  *
  * Actor relationships:
  *   - Parent: root (top-level, created by `GameServer` only when tracing is enabled)
  *   - Receives from: every [[TracingInterceptor]] instance system-wide (`Record`)
  */
object TraceCollector {
  sealed trait Command

  /** Append `event` to the buffer, evicting the oldest event if the buffer is already at capacity. */
  final case class Record(event: TraceEvent) extends Command

  /** Reply with the buffered events, oldest first. */
  final case class GetRecent(replyTo: ActorRef[Vector[TraceEvent]]) extends Command

  /** @param bufferSize the maximum number of events retained; typically [[TracingConfig.bufferSize]] */
  def apply(bufferSize: Int): Behavior[Command] = running(Vector.empty, bufferSize)

  private def running(buffer: Vector[TraceEvent], bufferSize: Int): Behavior[Command] =
    Behaviors.receiveMessage {
      case Record(event) =>
        running((buffer :+ event).takeRight(bufferSize), bufferSize)

      case GetRecent(replyTo) =>
        replyTo ! buffer
        Behaviors.same
    }
}
