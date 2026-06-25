package com.andy327.actor.events

/** Emit seam for analytics events. The actor layer publishes [[GameEvent]]s through this without knowing how
  * (or whether) they are consumed; the consumer (`AnalyticsConsumer`) lives entirely on the other side of the seam.
  *
  * Publishing is fire-and-forget: callers are never blocked and delivery failures are silently discarded — analytics
  * must never affect gameplay.
  */
trait EventPublisher {

  /** Publish a single analytics event. */
  def publish(event: GameEvent): Unit
}

/** No-op publisher used in tests and single-instance setups that do not run the analytics pipeline. */
object NoOpEventPublisher extends EventPublisher {
  override def publish(event: GameEvent): Unit = ()
}
