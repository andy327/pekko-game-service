package com.andy327.model

import java.util.UUID

package object core {

  /** Identifier for a room: a durable context that can host a sequence of matches over its lifetime. */
  type RoomId = UUID

  /** Identifier for a single match — one playthrough. Minted fresh per match, so each match's records stay distinct
    * from earlier matches in the same room.
    */
  type MatchId = UUID

  /** Unique identifier for a player. */
  type PlayerId = UUID
}
