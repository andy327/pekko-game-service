package com.andy327.actor.tracing

import java.time.Instant

import scala.util.Random

import org.apache.pekko.actor.typed.BehaviorInterceptor.{ReceiveTarget, SignalTarget}
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{Behavior, BehaviorInterceptor, Signal, TypedActorContext}

/** A [[BehaviorInterceptor]] that emits a [[TraceEvent]] for each message received by the wrapped actor.
  *
  * Install via [[TracingInterceptor.wrap]]; that helper is a no-op when `config.enabled` is `false`, so disabled
  * tracing costs nothing after startup — no interceptor is instantiated, no events are allocated.
  *
  * Because typed Pekko does not expose the sender on received messages, `TraceEvent.from` is always `None`. The `to`
  * field is the receiving actor's path, available via the interceptor context.
  *
  * Uses `classOf[AnyRef]` as the `interceptMessageClass` so the interceptor fires for every received message
  * regardless of the concrete command type. This avoids requiring a `ClassTag` at call sites where the actor's
  * command type is a path-dependent abstract type member (e.g. `bundle.actor.Command` in [[GameManager]]).
  *
  * `isSame` uses reference equality so that each interceptor instance is treated as distinct by Pekko's
  * interceptor-deduplication logic, allowing the same class to be applied independently to different actors.
  */
final private class TracingInterceptor[T](config: TracingConfig, emit: TraceEvent => Unit)
    extends BehaviorInterceptor[T, T](classOf[AnyRef].asInstanceOf[Class[T]]) {

  override def aroundReceive(ctx: TypedActorContext[T], msg: T, target: ReceiveTarget[T]): Behavior[T] = {
    if (config.sampleRate >= 1.0 || Random.nextDouble() < config.sampleRate)
      emit(
        TraceEvent(
          from = None,
          to = ctx.asScala.self.path.toString,
          messageType = msg.getClass.getSimpleName,
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
