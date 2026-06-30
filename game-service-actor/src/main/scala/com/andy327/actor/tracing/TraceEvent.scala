package com.andy327.actor.tracing

import java.time.Instant

/** A single actor message receipt recorded by the tracing layer.
  *
  * @param from actor path of the message sender. Always `None` under the current [[TracingInterceptor]]
  *             implementation: typed Pekko exposes no sender on a received message (protocols carry `replyTo`
  *             explicitly instead of an implicit envelope sender), and a `BehaviorInterceptor` only observes the
  *             receiving side, so there is no hook to capture who sent a message. The field is kept on the model
  *             because a sender is required to draw edges in a flow visualization; populating it will need a
  *             different or additional capture mechanism, which is out of scope for this branch.
  * @param to actor path of the receiving actor
  * @param messageType simple class name of the received message
  * @param timestamp wall-clock time at which the interceptor observed the message
  */
final case class TraceEvent(
    from: Option[String],
    to: String,
    messageType: String,
    timestamp: Instant
)
