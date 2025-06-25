package com.andy327.server.lobby

sealed trait GameLifecycleStatus

object GameLifecycleStatus {
  case object WaitingForPlayers extends GameLifecycleStatus
  case object ReadyToStart extends GameLifecycleStatus
  case object InProgress extends GameLifecycleStatus

  sealed trait GameEnded extends GameLifecycleStatus
  case object Completed extends GameEnded // game ended normally (win/draw)
  case object Cancelled extends GameEnded // abandoned or invalidated
}
