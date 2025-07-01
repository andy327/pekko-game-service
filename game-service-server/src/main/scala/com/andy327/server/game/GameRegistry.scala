package com.andy327.server.game

import com.andy327.model.core.GameType
import com.andy327.server.actors.core.GameActor
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.game.modules.{GameModule, TicTacToeModule}

/**
 * Groups together all game-specific components required to support a particular GameType.
 *
 * @param module the logic for parsing moves and mapping a GameOperation to actor commands
 * @param actor the lifecycle implementation for creating and restoring game actors
 */
case class GameModuleBundle(module: GameModule, actor: GameActor[_, _])

/**
 * Registry that maps each supported GameType to its corresponding GameModuleBundle.
 *
 * This allows GameManager and GameRoutes to generically handle games without embedding any game-specific logic or
 * types. Adding a new game involves registering it here.
 */
object GameRegistry {

  /**
   * Retrieves the GameModuleBundle associated with a given GameType.
   *
   * @param gameType the game type to look up
   * @return an optional bundle containing the game module and actor implementation
   */
  def forType(gameType: GameType): GameModuleBundle = gameType match {
    case GameType.TicTacToe => GameModuleBundle(TicTacToeModule, TicTacToeActor)
  }
}
