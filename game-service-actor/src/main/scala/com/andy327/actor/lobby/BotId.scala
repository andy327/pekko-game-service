package com.andy327.actor.lobby

import java.util.UUID

import com.andy327.model.core.PlayerId

/** Mints and recognizes the reserved `PlayerId`s that bot seats occupy.
  *
  * A bot is a full lobby member — seated by the host, counted toward the roster, listed in `LobbyMetadata.players`,
  * and dealt into the game like anyone else — but it holds no account and never connects, so its identity is derived
  * rather than registered: every bot id shares `Marker` as its most significant bits and carries its ordinal in the
  * least significant. Bot-ness is therefore decidable from the id alone, anywhere it travels — lobby metadata, game
  * rosters, persisted snapshots — with no flag to thread through commands or storage.
  */
object BotId {

  /** The most significant bits of every bot id. The version nibble this encodes is not 4, so a registered account's
    * random (version 4) UUID can never collide with a bot id.
    */
  private val Marker: Long = 0xb07b07b07b07b07bL

  /** True exactly when `id` names a bot minted by [[forOrdinal]]. */
  def isBot(id: PlayerId): Boolean = id.getMostSignificantBits == Marker

  /** The bot id with the given zero-based ordinal; deterministic, so the same ordinal always names the same bot. */
  def forOrdinal(ordinal: Int): PlayerId = new UUID(Marker, ordinal.toLong)

  /** The seated [[Player]] for `ordinal`, displayed as "Bot 1", "Bot 2", … */
  def player(ordinal: Int): Player = Player(forOrdinal(ordinal), s"Bot ${ordinal + 1}")

  /** The lowest-ordinal bot not already seated among `seated` — the next bot a lobby should add, so a room's bots are
    * always named "Bot 1", "Bot 2", … without gaps, and removing then re-adding reuses the freed name.
    */
  def nextFor(seated: Set[PlayerId]): Player = {
    val ordinal = Iterator.from(0).find(n => !seated.contains(forOrdinal(n))).get
    player(ordinal)
  }
}
