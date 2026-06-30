package com.andy327.actor.tracing

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior, Terminated}

/** Holds the most recent [[TraceEvent]]s in a bounded in-memory buffer, and fans live events out to subscribers, for
  * a debug/visualization endpoint.
  *
  * Each [[TracingInterceptor]] installed across the actor system sends its events here via [[Record]]. The buffer
  * retains at most `bufferSize` events, dropping the oldest once full, so memory use stays bounded regardless of how
  * long the system runs or how chatty tracing is.
  *
  * A caller registers for live delivery via [[Subscribe]]: the existing buffer is replayed to it immediately (oldest
  * first), then every subsequent [[Record]] is forwarded as it arrives, so there is no gap between the backlog and
  * live events. [[Unsubscribe]] stops delivery; a subscriber is also dropped automatically if it terminates, mirroring
  * [[com.andy327.actor.core.LobbyManager]]'s subscriber cleanup.
  *
  * Actor relationships:
  *   - Parent: root (top-level, created by `GameServer` only when tracing is enabled)
  *   - Receives from: every [[TracingInterceptor]] instance system-wide (`Record`); the debug WebSocket route
  *     (`Subscribe`/`Unsubscribe`)
  *   - Sends to: each subscribed `ActorRef[TraceEvent]` (the debug WebSocket route's outbound stream)
  */
object TraceCollector {
  sealed trait Command

  /** Append `event` to the buffer, evicting the oldest event if the buffer is already at capacity, and forward it to
    * every current subscriber.
    */
  final case class Record(event: TraceEvent) extends Command

  /** Reply with the buffered events, oldest first. */
  final case class GetRecent(replyTo: ActorRef[Vector[TraceEvent]]) extends Command

  /** Register `subscriber` for live events: the current buffer is replayed to it immediately, then every later
    * [[Record]] is forwarded as it arrives.
    */
  final case class Subscribe(subscriber: ActorRef[TraceEvent]) extends Command

  /** Deregister `subscriber` from live events. Idempotent — a no-op if it was not subscribed. */
  final case class Unsubscribe(subscriber: ActorRef[TraceEvent]) extends Command

  /** @param bufferSize the maximum number of events retained; typically [[TracingConfig.bufferSize]] */
  def apply(bufferSize: Int): Behavior[Command] = running(Vector.empty, bufferSize, Set.empty)

  private def running(
      buffer: Vector[TraceEvent],
      bufferSize: Int,
      subscribers: Set[ActorRef[TraceEvent]]
  ): Behavior[Command] =
    Behaviors
      .receive[Command] { (context, message) =>
        message match {
          case Record(event) =>
            subscribers.foreach(_ ! event)
            running((buffer :+ event).takeRight(bufferSize), bufferSize, subscribers)

          case GetRecent(replyTo) =>
            replyTo ! buffer
            Behaviors.same

          case Subscribe(subscriber) =>
            context.watch(subscriber)
            buffer.foreach(subscriber ! _)
            running(buffer, bufferSize, subscribers + subscriber)

          case Unsubscribe(subscriber) =>
            context.unwatch(subscriber)
            running(buffer, bufferSize, subscribers - subscriber)
        }
      }
      .receiveSignal { case (_, Terminated(ref)) =>
        running(buffer, bufferSize, subscribers.filterNot(_ == ref))
      }
}
