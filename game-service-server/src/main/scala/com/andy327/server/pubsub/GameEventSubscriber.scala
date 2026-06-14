package com.andy327.server.pubsub

import java.util.UUID

import scala.util.Try

import cats.effect.{IO, Ref}

import fs2.Stream
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.GameId
import com.andy327.server.actors.core.PlayerActor

/** Subscribes to the `game-events:*` Redis pattern and routes incoming messages to locally connected PlayerActors.
  *
  * In multi-instance deployments, players watching a game that runs on a different server instance register here.
  * When the remote game actor publishes an event to Redis, this subscriber receives it and forwards the raw JSON to
  * each registered PlayerActor via `PlayerActor.SendRawJson`, bypassing local deserialization.
  *
  * In single-instance deployments, the registry is always empty — game actors fan-out directly to local subscribers
  * and the Redis messages they publish are silently dropped by this subscriber.
  *
  * @param eventStream pre-built stream of `(channel, message)` pairs from Redis pub/sub; provided by
  *                    [[RedisPubSubResource]] to avoid carrying the redis4cats streaming type parameter
  * @param registry map from GameId to the set of locally connected PlayerActor refs watching that game
  */
class GameEventSubscriber(
    eventStream: Stream[IO, (String, String)],
    registry: Ref[IO, Map[GameId, Set[ActorRef[PlayerActor.Command]]]]
) {
  private val channelPrefix = "game-events:"

  /** Register `ref` to receive forwarded events for `gameId` (game is hosted on a remote instance). */
  def registerPlayer(gameId: GameId, ref: ActorRef[PlayerActor.Command]): IO[Unit] =
    registry.update(reg => reg + (gameId -> (reg.getOrElse(gameId, Set.empty) + ref)))

  /** Remove all PlayerActor registrations for `gameId` (e.g., after the game completes on any instance). */
  def unregisterGame(gameId: GameId): IO[Unit] =
    registry.update(_ - gameId)

  /** Remove `ref` from every game it watches (e.g., when the player disconnects), dropping games left with no
    * remaining watchers so the registry does not accumulate empty entries.
    */
  def unregisterPlayer(ref: ActorRef[PlayerActor.Command]): IO[Unit] =
    registry.update(_.flatMap { case (gameId, refs) =>
      val remaining = refs - ref
      if (remaining.isEmpty) None else Some(gameId -> remaining)
    })

  /** Processes incoming Redis messages and routes each one to the registered actors for that game.
    *
    * Runs until cancelled — should be started as a background fiber before the HTTP server accepts connections.
    * The stream terminates naturally if the Redis connection is lost.
    */
  def run: IO[Unit] =
    eventStream
      .evalMap { case (channel, data) =>
        Try(UUID.fromString(channel.stripPrefix(channelPrefix))).toOption match {
          case Some(gameId) =>
            registry.get.flatMap { reg =>
              IO(reg.getOrElse(gameId, Set.empty).foreach(_ ! PlayerActor.SendRawJson(data)))
            }
          case None => IO.unit
        }
      }
      .compile
      .drain
}

object GameEventSubscriber {

  /** Allocate a fresh, empty registry and return a ready-to-run subscriber.
    *
    * @param eventStream the stream of `(channel, message)` pairs from Redis pub/sub
    */
  def create(eventStream: Stream[IO, (String, String)]): IO[GameEventSubscriber] =
    Ref
      .of[IO, Map[GameId, Set[ActorRef[PlayerActor.Command]]]](Map.empty)
      .map(new GameEventSubscriber(eventStream, _))
}
