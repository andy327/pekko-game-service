package com.andy327.server.actors.core

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.server.lobby.Player

/** An actor representing an individual player's session.
  *
  * Receives push events from LobbyManager and game actors and forwards them, as domain [[PlayerEvent]]s, to the
  * outbound session stream via the `sessionOut` ActorRef. Rendering each event to JSON and writing it to the WebSocket
  * is the transport layer's responsibility (see [[com.andy327.server.http.routes.WebSocketRoutes]]), so this actor
  * stays free of any serialization or transport concern.
  *
  * Actor relationships:
  *   - Parent: [[PlayerManager]]
  *   - Receives from: [[LobbyManager]] and game actors (fan-out `SendEvent`), [[PlayerManager]] (`Disconnect`)
  *   - Sends to: the outbound session stream via `sessionOut` (`SessionEvent` frames, `SessionComplete` on disconnect)
  */
object PlayerActor {
  sealed trait Command

  /** Forward `event` to the player's outbound session stream. */
  final case class SendEvent(event: PlayerEvent) extends Command

  /** Terminate this actor and close the associated session stream. */
  case object Disconnect extends Command

  /** Protocol for the outbound stream backing a player session.
    *
    * The ActorSource in [[com.andy327.server.http.routes.WebSocketRoutes]] is materialized over this type so that the
    * stream can be driven from the actor side: [[SessionEvent]] delivers a domain event for the transport layer to
    * render and push, and [[SessionComplete]] matches the source's `completionMatcher` and closes the stream from the
    * server.
    */
  sealed trait SessionOutput

  /** A domain event to deliver to the player's client. */
  final case class SessionEvent(event: PlayerEvent) extends SessionOutput

  /** Completes the outbound stream, closing the session from the server side. */
  case object SessionComplete extends SessionOutput

  def apply(player: Player, sessionOut: ActorRef[SessionOutput]): Behavior[Command] =
    Behaviors.setup { context =>
      context.log.info(s"PlayerActor started for player ${player.name} (${player.id})")
      Behaviors.receiveMessage {
        case SendEvent(event) =>
          sessionOut ! SessionEvent(event)
          Behaviors.same
        case Disconnect =>
          sessionOut ! SessionComplete
          Behaviors.stopped
      }
    }
}
