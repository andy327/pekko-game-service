package com.andy327.actor.tracing

import java.time.Instant

/** A single actor message receipt recorded by the tracing layer.
  *
  * @param from actor path of the message sender; `None` when the sender cannot be determined (e.g. dead-letters or
  *             system messages)
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
