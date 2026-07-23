package com.andy327.actor.bot

import com.andy327.model.core.GameType

/** How strong an opponent a bot seat plays. A single level exists today; the seam keeps a stronger (or gentler) bot a
  * registry entry away rather than a redesign.
  */
sealed trait BotDifficulty

object BotDifficulty {

  /** The strength every bot currently plays at. */
  case object Standard extends BotDifficulty
}

/** Resolves the [[AiPolicy]] a bot plays for a given game type and difficulty.
  *
  * Every game type currently resolves to [[RandomPolicy]]; a per-game heuristic replaces its entry here without
  * touching the bot machinery or the other games.
  */
object BotPolicies {

  def forGame(gameType: GameType, difficulty: BotDifficulty = BotDifficulty.Standard): AiPolicy =
    (gameType, difficulty) match {
      case (_, BotDifficulty.Standard) => RandomPolicy
    }
}
