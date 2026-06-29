package com.andy327.actor.lobby

import cats.effect.IO

import com.andy327.model.core.RoomId

/** Repository interface for persisting and restoring lobby state across server restarts.
  *
  * Writes are fire-and-forget from the actor's perspective — `LobbyManager` calls these after each state-changing event
  * and does not await the result. [[loadAllLobbies]] is called once at startup to restore the in-memory lobby map.
  */
trait LobbyRepository {

  /** Persist (insert or update) a lobby's full metadata. */
  def saveLobby(metadata: LobbyMetadata): IO[Unit]

  /** Remove a lobby from persistent storage. Called when a lobby reaches a terminal state. */
  def deleteLobby(roomId: RoomId): IO[Unit]

  /** Load all persisted active lobbies. Used once at startup to restore the in-memory lobby map. */
  def loadAllLobbies(): IO[List[LobbyMetadata]]
}
