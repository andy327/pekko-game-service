package com.andy327.server.http.routes

import com.andy327.model.core.GameType
import com.andy327.server.game.{GameModuleBundle, GameRegistry}

/**
 * Provides a way to look up game-specific modules (e.g., logic for parsing moves and handling commands) based on a
 * given GameType. This abstraction allows decoupling the route and actor logic from a hard dependency on a specific
 * registry (like GameRegistry).
 *
 * This is useful for testing or extending the system to support dynamic game type loading.
 */
trait GameModuleProvider {
  def forType(gt: GameType): Option[GameModuleBundle]
}

/**
 * Default implementation of GameModuleProvider that delegates to GameRegistry.
 *
 * GameRegistry is a static map of GameType -> GameModuleBundle defined at application startup.
 */
object DefaultGameModuleProvider extends GameModuleProvider {
  override def forType(gt: GameType): Option[GameModuleBundle] = GameRegistry.forType(gt)
}
