package com.andy327.server.analytics

/** Emit seam for analytics events. The actor layer publishes [[GameAnalyticsEvent]]s through this without knowing how
  * (or whether) they are consumed; the consumer ([[AnalyticsConsumer]]) lives entirely on the other side of the seam.
  *
  * Publishing is fire-and-forget: callers are never blocked and delivery failures are silently discarded — analytics
  * must never affect gameplay.
  */
trait AnalyticsPublisher {

  /** Publish a single analytics event. */
  def publish(event: GameAnalyticsEvent): Unit
}

/** No-op publisher used in tests and single-instance setups that do not run the analytics pipeline. */
object NoOpAnalyticsPublisher extends AnalyticsPublisher {
  override def publish(event: GameAnalyticsEvent): Unit = ()
}
