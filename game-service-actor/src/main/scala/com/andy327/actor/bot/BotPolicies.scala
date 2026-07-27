package com.andy327.actor.bot

import com.andy327.model.core.GameType

/** How strong an opponent a bot seat plays — a property of the seat, chosen when the bot is added and carried with it
  * into the game.
  *
  * The ladder is deliberately universal rather than per-game: a player asks for a hard opponent without caring which
  * game they are playing, so one vocabulary serves every game type and one control serves the whole UI. What differs
  * per game is only which policy each rung resolves to, and a game that has not implemented a rung yet falls back to
  * the closest one it has (see [[BotPolicies]]).
  *
  * Adding a rung is a new case object here plus its per-game entries in [[BotPolicies]] — nothing else in the bot
  * machinery changes.
  */
sealed trait BotDifficulty {

  /** The stable lower-case name this level travels under on the wire. */
  def label: String
}

object BotDifficulty {

  /** The baseline strength: legal moves chosen at random, with no strategy. */
  case object Standard extends BotDifficulty { val label = "standard" }

  /** A strong opponent that plays each game as well as its per-game policy allows (optimally, where one exists). */
  case object Hard extends BotDifficulty { val label = "hard" }

  /** Every level a caller may ask for, in ascending order of strength. */
  val all: List[BotDifficulty] = List(Standard, Hard)

  /** The level a bot takes when none is requested. */
  val Default: BotDifficulty = Standard

  private val byLabel: Map[String, BotDifficulty] = all.map(level => level.label -> level).toMap

  /** Resolves a (case-insensitive) wire name to its level, or `None` if it is not a known one. */
  def fromString(s: String): Option[BotDifficulty] = byLabel.get(s.toLowerCase)
}

/** Resolves the [[AiPolicy]] a bot plays, given its game type and the difficulty its seat was created with.
  *
  * `Standard` resolves to [[RandomPolicy]] for every game. `Hard` resolves to a per-game policy where one exists —
  * [[TicTacToePolicy]] for Tic-Tac-Toe — and falls back to the random baseline for a game that has no Hard policy yet,
  * rather than failing. A per-game heuristic replaces one entry here without touching the bot machinery or the other
  * games, so a new rung can be rolled out one game at a time.
  */
object BotPolicies {

  def forGame(gameType: GameType, difficulty: BotDifficulty = BotDifficulty.Default): AiPolicy =
    (gameType, difficulty) match {
      case (GameType.TicTacToe, BotDifficulty.Hard) => TicTacToePolicy
      case (_, BotDifficulty.Hard)                  => RandomPolicy
      case (_, BotDifficulty.Standard)              => RandomPolicy
    }
}
