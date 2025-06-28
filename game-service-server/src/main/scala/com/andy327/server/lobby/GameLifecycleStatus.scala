package com.andy327.server.lobby

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
  case object WaitingForPlayers extends GameLifecycleStatus
  case object ReadyToStart extends GameLifecycleStatus
  case object InProgress extends GameLifecycleStatus

  sealed trait GameEnded extends GameLifecycleStatus
  case object Completed extends GameEnded // game ended normally (win/draw)
  case object Cancelled extends GameEnded // abandoned or invalidated
}
