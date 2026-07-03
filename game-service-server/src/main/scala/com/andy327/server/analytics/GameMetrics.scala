package com.andy327.server.analytics

import io.prometheus.client.{CollectorRegistry, Counter, Histogram}

import com.andy327.actor.events.GameEvent
import com.andy327.actor.events.GameEvent._
import com.andy327.model.core.GameType

/** Prometheus metrics for game analytics. Registers a small, low-cardinality set of collectors on `registry` and
  * folds each `GameEvent` into them via [[record]].
  *
  * Dimensions are kept coarse on purpose — `game_type` (one of three) and a game `outcome` (won/draw/forfeit). Nothing
  * here is keyed on player identity; per-player statistics need a durable identity that does not exist yet (#37).
  *
  * @param registry the registry the collectors are registered on; the same instance backs `GET /metrics`
  */
class GameMetrics(registry: CollectorRegistry) {

  private val gamesStarted: Counter = Counter
    .build()
    .name("games_started_total")
    .help("Total number of games started, by game type.")
    .labelNames("game_type")
    .register(registry)

  private val gamesCompleted: Counter = Counter
    .build()
    .name("games_completed_total")
    .help("Total number of games completed, by game type and outcome (won/draw/forfeit).")
    .labelNames("game_type", "outcome")
    .register(registry)

  private val moves: Counter = Counter
    .build()
    .name("moves_total")
    .help("Total number of moves applied, by game type.")
    .labelNames("game_type")
    .register(registry)

  private val gameMoves: Histogram = Histogram
    .build()
    .name("game_moves")
    .help("Distribution of the number of moves played per completed game, by game type.")
    .labelNames("game_type")
    .buckets(5, 9, 10, 20, 30, 42, 60, 100, 200)
    .register(registry)

  private val chatMessages: Counter = Counter
    .build()
    .name("chat_messages_total")
    .help("Total number of chat messages sent; game_type is \"lobby\" for pre-game messages.")
    .labelNames("game_type")
    .register(registry)

  /** Fold a single analytics event into the metrics. */
  def record(event: GameEvent): Unit = event match {
    case GameStarted(_, gameType, _) =>
      gamesStarted.labels(label(gameType)).inc()
    case MoveMade(_, gameType, _, _) =>
      moves.labels(label(gameType)).inc()
    case GameCompleted(_, gameType, outcome, moveCount) =>
      gamesCompleted.labels(label(gameType), outcome.label).inc()
      gameMoves.labels(label(gameType)).observe(moveCount.toDouble)
    case ChatSent(_, gameType) =>
      chatMessages.labels(gameType.map(label).getOrElse("lobby")).inc()
  }

  private def label(gameType: GameType): String = gameType match {
    case GameType.TicTacToe   => "tictactoe"
    case GameType.ConnectFour => "connectfour"
    case GameType.Battleship  => "battleship"
    case GameType.Pig         => "pig"
    case GameType.Mastermind  => "mastermind"
    case GameType.LiarsDice   => "liarsdice"
  }
}
