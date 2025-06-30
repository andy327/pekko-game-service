package com.andy327.server.game

import com.andy327.model.core.GameType
import com.andy327.server.game.modules.{GameModule, TicTacToeModule}

/**
 * Registry that maps each supported GameType to its corresponding GameModule.
 * Used by GameManager and GameRoutes to delegate game-specific behavior.
 */
object GameRegistry {

  // Map of supported game types to their modules
  private val modules: Map[GameType, GameModule] = Map(
    GameType.TicTacToe -> TicTacToeModule
  )

  /**
   * Retrieves the GameModule implementation for a given GameType.
   *
   * @param gameType the game type to look up
   * @return the corresponding module, if registered
   */
  def forType(gameType: GameType): Option[GameModule] = modules.get(gameType)
}
