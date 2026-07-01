package com.andy327.actor.tracing

import java.time.Instant

/** A single actor message receipt recorded by the tracing layer.
  *
  * @param to actor path of the receiving actor
  * @param messageType simple class name of the received message
  * @param timestamp wall-clock time at which the interceptor observed the message
  */
final case class TraceEvent(
    to: String,
    messageType: String,
    timestamp: Instant
)
