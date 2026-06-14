package com.andy327.server.pubsub

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.syntax._

import com.andy327.model.core.GameId
import com.andy327.server.actors.core.PlayerEvent
import com.andy327.server.http.json.JsonProtocol.playerEventEncoder

/** Contract for publishing game events so other server instances can relay them to local players.
  *
  * The operation is fire-and-forget: callers are not blocked and delivery failures are silently discarded.
  */
trait GameEventPublisher {

  /** Publish `event` for `gameId` to observers on other server instances.
    *
    * @param gameId identifies the game whose state changed
    * @param event the event to broadcast
    */
  def publish(gameId: GameId, event: PlayerEvent): Unit
}

/** No-op publisher used in single-instance deployments where cross-instance fan-out is not needed. */
object NoOpGameEventPublisher extends GameEventPublisher {
  override def publish(gameId: GameId, event: PlayerEvent): Unit = ()
}

/** Redis-backed publisher. Serializes each event to JSON and publishes to `game-events:{gameId}` asynchronously.
  *
  * @param doPublish function that fires a publish command: `(channel, message) => IO[Unit]`; provided by
  *                  [[RedisPubSubResource]] to avoid carrying the redis4cats streaming type parameter
  */
class RedisGameEventPublisher(doPublish: (String, String) => IO[Unit])(implicit runtime: IORuntime)
    extends GameEventPublisher {

  override def publish(gameId: GameId, event: PlayerEvent): Unit = {
    val channel = s"game-events:$gameId"
    val json = event.asJson.deepDropNullValues.noSpaces
    doPublish(channel, json).unsafeRunAndForget()
  }
}
