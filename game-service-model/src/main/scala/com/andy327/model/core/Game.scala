package com.andy327.model.core

/** A generic interface for modeling turn-based games.
  *
  * This trait abstracts over the concrete details of a specific game by parameterizing over the types involved: moves,
  * players, game state, game status, and errors.
  *
  * By implementing this trait, a game defines the basic mechanics for tracking state and applying moves
  *
  * Type Parameters:
  *   - Move: type that represents a single player action (e.g., placing a piece)
  *   - State: type that represents the current viewable state of the game
  *   - Player: type that identifies a player (e.g., X or O)
  *   - Status: type that indicates whether the game is ongoing, won, drawn, etc.
  *   - Error: type that describes what can go wrong during play (e.g., invalid move)
  */
trait Game[Move, State, Player, Status, Error] {

  /** The current state of the game (e.g., the board, or the object itself). */
  def currentState: State

  /** The player whose turn it is to move. */
  def currentPlayer: Player

  /** The current status of the game (e.g., ongoing, won, draw). */
  def gameStatus: Status

  /** The number of moves applied so far. Serves as the 0-based ordinal of the next move in the history log, and is
    * derived from the game's own state so it survives a restart without consulting the move log.
    */
  def moveCount: Int

  /** Resolves an external player ID to this game's player token (e.g., a mark or seat).
    *
    * Returns None if the ID does not belong to a participant. This is the single place where platform identity
    * (PlayerId) is mapped to game identity (the `Player` type parameter), letting game-agnostic code route moves
    * without knowing how a given game seats its players.
    */
  def playerFor(playerId: PlayerId): Option[Player]

  /** The participating players, in seat order.
    *
    * Where [[playerFor]] maps a platform [[PlayerId]] to this game's internal player token, this exposes the roster
    * itself, letting game-agnostic code enumerate participants — e.g. to record a per-player result on completion —
    * without knowing how a given game seats them.
    */
  def players: List[PlayerId]

  /** Attempts to apply a move made by a player.
    *
    * This method should:
    *   - Validate whether the move is allowed
    *   - Update the game state accordingly
    *   - Return either a new game state or an error
    */
  def play(player: Player, move: Move): Either[Error, State]

  /** Applies the effect of `playerId` leaving an in-progress game.
    *
    * The returned state's [[gameStatus]] drives what happens next, exactly as for a move: a terminal status (won or
    * draw) runs the game's normal completion machinery, while an `InProgress` status means the game continues with the
    * remaining players (the fold-out case for games with more than two players). A `Left` rejects the leave and leaves
    * the game untouched.
    *
    * Each game defines its own leave semantics: two-player games forfeit — the leaver loses and the opponent wins — so
    * the implementation should reject a non-participant and an already-finished game, then transition to a win for the
    * remaining player. Returns a core [[GameError]] rather than the game-specific `Error` type because leaving never
    * produces a game-rule error.
    */
  def playerLeft(playerId: PlayerId): Either[GameError, State]
}
