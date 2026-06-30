package com.andy327.actor.tracing

import java.time.Instant

import scala.util.Random

import org.apache.pekko.actor.typed.BehaviorInterceptor.{ReceiveTarget, SignalTarget}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{Behavior, BehaviorInterceptor, Signal, TypedActorContext}

/** A [[BehaviorInterceptor]] that passes every message received by the wrapped actor straight through, while emitting a
  * [[TraceEvent]] for it (subject to the message type's effective sample rate, see [[TracingConfig.sampleRateFor]]).
  *
  * Install via [[TracingInterceptor.wrap]]; that helper is a no-op when `config.enabled` is `false`, so disabled
  * tracing costs nothing after startup — no interceptor is instantiated, no events are allocated.
  *
  * Because typed Pekko does not expose the sender on received messages, `TraceEvent.from` is always `None`. The `to`
  * field is the receiving actor's path, available via the interceptor context.
  *
  * Passes `classOf[AnyRef]` (via an unchecked cast to `Class[T]`) as the `interceptMessageClass` constructor argument,
  * so Pekko's runtime `isInstance` check accepts every message regardless of the concrete command type — safe because
  * `aroundReceive` only ever receives messages the actor's own mailbox has already typed as `T`.
  *
  * `isSame` uses reference equality so that each interceptor instance is treated as distinct by Pekko's
  * interceptor-deduplication logic, allowing the same class to be applied independently to different actors.
  */
final private class TracingInterceptor[T](config: TracingConfig, emit: TraceEvent => Unit)
    extends BehaviorInterceptor[T, T](classOf[AnyRef].asInstanceOf[Class[T]]) {

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    val messageType = msg.getClass.getSimpleName
    val rate = config.sampleRateFor(messageType)
    if (rate >= 1.0 || Random.nextDouble() < rate)
      emit(
        TraceEvent(
          from = None,
          to = ctx.asScala.self.path.toString,
          messageType = messageType,
          timestamp = Instant.now()
        )
      )
    target(ctx, msg)
  }

  override def aroundSignal(ctx: TypedActorContext[T], signal: Signal, target: SignalTarget[T]): Behavior[T] =
    target(ctx, signal)

  override def isSame(other: BehaviorInterceptor[Any, Any]): Boolean = this eq other
}

object TracingInterceptor {

  /** Wraps `behavior` with the tracing interceptor when `config.enabled` is `true`; returns `behavior` unchanged
    * otherwise. Callers use this at every actor spawn point so the gating lives in one place.
    */
  def wrap[T](behavior: Behavior[T], config: TracingConfig, emit: TraceEvent => Unit): Behavior[T] =
    if (!config.enabled) behavior
    else Behaviors.intercept(() => new TracingInterceptor[T](config, emit))(behavior)
}
