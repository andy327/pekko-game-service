package com.andy327.server.analytics

import com.andy327.model.core.{GameId, GameType, PlayerId}

/** Structured domain events describing a game's lifecycle, emitted from the actor layer for analytics. Unlike
  * [[com.andy327.server.actors.core.PlayerEvent]] (which carries rendered board state for WebSocket delivery), these
  * carry only the dimensions analytics needs. Published on the `game-analytics` channel and decoded by
  * [[AnalyticsConsumer]]; the wire codec lives in [[GameAnalyticsCodecs]], keeping this ADT free of any serialization
  * concern.
  */
sealed trait GameAnalyticsEvent {

  /** The game this event refers to. */
  def gameId: GameId
}

object GameAnalyticsEvent {

  /** How a game ended. `Won`/`Draw` are reached through normal play; `Forfeit` when a player leaves an in-progress
    * game. Winner identity is intentionally omitted here — per-player win/loss/draw results are recorded against
    * durable identity in the `player_games` history table instead, keeping these analytics events aggregate.
    */
  sealed trait Outcome {

    /** Stable lower-case label used as a Prometheus dimension and as the JSON wire value. */
    def label: String
  }

  object Outcome {
    case object Won extends Outcome { val label = "won" }
    case object Draw extends Outcome { val label = "draw" }
    case object Forfeit extends Outcome { val label = "forfeit" }

    val all: List[Outcome] = List(Won, Draw, Forfeit)

    def fromLabel(s: String): Option[Outcome] = all.find(_.label == s)
  }

  /** A new game was created and started. */
  final case class GameStarted(gameId: GameId, gameType: GameType, playerCount: Int) extends GameAnalyticsEvent

  /** A move was successfully applied. `seq` is the 0-based ordinal of the move within the game. */
  final case class MoveMade(gameId: GameId, gameType: GameType, playerId: PlayerId, seq: Int) extends GameAnalyticsEvent

  /** A game reached a terminal state. `moveCount` is the total number of moves played. */
  final case class GameCompleted(gameId: GameId, gameType: GameType, outcome: Outcome, moveCount: Int)
      extends GameAnalyticsEvent

  /** A chat message was sent. `gameType` is `None` for a message sent in a lobby before the game starts. */
  final case class ChatSent(gameId: GameId, gameType: Option[GameType]) extends GameAnalyticsEvent
}
