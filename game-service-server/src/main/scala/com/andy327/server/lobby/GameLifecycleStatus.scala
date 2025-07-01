package com.andy327.server.lobby

/**
 * Represents the lifecycle state of a game lobby or active game session.
 *
 * This is used by the GameManager and LobbyManager to track the current state of a game throughout its lifecycle, from
 * creation to completion or cancellation.
 */
sealed trait GameLifecycleStatus {

  /**
   * Indicates whether a game is in a lifecycle state that permits players to attempt joining.
   *
   * Note: This does not check whether the lobby has room for additional players. That check must still be done
   * separately based on player count and game type limits.
   */
  def isJoinable: Boolean = this match {
    case GameLifecycleStatus.WaitingForPlayers | GameLifecycleStatus.ReadyToStart =>
      true
    case _ =>
      false
  }
}

object GameLifecycleStatus {

  /** Initial state for a game that has been created but not yet filled. */
  case object WaitingForPlayers extends GameLifecycleStatus

  /** State indicating the lobby has enough players and is ready to begin the game. */
  case object ReadyToStart extends GameLifecycleStatus

  /** Indicates that the game has started and is currently in progress. */
  case object InProgress extends GameLifecycleStatus

  /** Trait representing any terminal game state - where the game has ended and can no longer be interacted with. */
  sealed trait GameEnded extends GameLifecycleStatus

  /** The game ended normally - either via win or draw. */
  case object Completed extends GameEnded

  /** The game was cancelled before completion - due to players leaving, disconnection, or host termination. */
  case object Cancelled extends GameEnded
}
