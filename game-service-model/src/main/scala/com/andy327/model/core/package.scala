package com.andy327.model

import java.util.UUID

package object core {

  /** Unique identifier for a game instance. */
  type GameId = UUID

  /** Unique identifier for a player. */
  type PlayerId = UUID
}
