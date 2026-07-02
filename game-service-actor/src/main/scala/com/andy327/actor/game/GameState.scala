package com.andy327.actor.game

import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Seat}
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.{Draw, Game, GameStatus, Mark, PlayerId, Won}
import com.andy327.model.mastermind.{Codemaker, Mastermind, Role}
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.TicTacToe

/** Super-type for all serializable “view” representations of game state that can be sent to the client as part of an
  * HTTP response.
  */
sealed trait GameState

/** Serializable view of any grid-based game state, suitable for HTTP responses.
  *
  * Shared by every game whose state is a board of cells each holding an optional mark: cells carry the mark's symbol
  * or `""` when empty, and the current player and winner are identified by their mark symbols.
  */
case class GridGameState(
    board: Vector[Vector[String]],
    currentPlayer: String,
    winner: Option[String],
    draw: Boolean
) extends GameState

object GridGameState {

  /** Builds the view from any board of optional marks plus the game's current player and status. */
  def of(board: Vector[Vector[Option[Mark]]], currentPlayer: Mark, status: GameStatus[Mark]): GridGameState = {
    val cells = board.map(_.map(_.map(_.toString).getOrElse(""))) // string-based representation for JSON
    val winner = status match {
      case Won(mark) => Some(mark.toString)
      case _         => None
    }
    GridGameState(cells, currentPlayer.toString, winner, status == Draw)
  }
}

/** Serializable, per-viewer view of a Battleship game.
  *
  * `board1` and `board2` are each projected for the requesting viewer: the viewer's own board reveals their ships,
  * while the opponent's board (and, for a spectator, both boards) is fog-of-war — only fired cells are shown, so ship
  * positions are never leaked until hit. Each cell is one of `hit`, `miss`, `ship`, `water`, or `unknown`.
  *
  * @param viewerSeat the seat the viewer occupies (`"P1"`/`"P2"`), or `None` for a spectator
  */
case class BattleshipState(
    board1: Vector[Vector[String]],
    board2: Vector[Vector[String]],
    currentPlayer: String,
    winner: Option[String],
    viewerSeat: Option[String]
) extends GameState

object BattleshipState {

  /** Builds the view of `game` for the given viewer seat (`None` = spectator, who sees both boards fogged). */
  def of(game: Battleship, viewerSeat: Option[Seat]): BattleshipState =
    BattleshipState(
      board1 = project(game.board1, revealShips = viewerSeat.contains(Player1)),
      board2 = project(game.board2, revealShips = viewerSeat.contains(Player2)),
      currentPlayer = game.currentPlayer.symbol,
      winner = game.winner.map(_.symbol),
      viewerSeat = viewerSeat.map(_.symbol)
    )

  /** Projects one player's board to cell tokens. When `revealShips` (the viewer's own board), un-hit ships show as
    * `ship` and empty cells as `water`; otherwise un-fired cells are `unknown`, hiding ship positions until hit.
    */
  private def project(b: PlayerBoard, revealShips: Boolean): Vector[Vector[String]] = {
    val shipCells = b.shipCells
    Vector.tabulate(Battleship.Size, Battleship.Size) { (r, c) =>
      val coord = Coord(r, c)
      val isShip = shipCells.contains(coord)
      if (b.shots.contains(coord)) if (isShip) "hit" else "miss"
      else if (revealShips) if (isShip) "ship" else "water"
      else "unknown"
    }
  }
}

/** Serializable view of a Pig game, pushed to all clients over WebSocket.
  *
  * Scores are indexed in seat order: `"P1"` → index 0, `"P2"` → index 1, etc. `viewerSeat` identifies the viewer
  * ("P1", "P2", …) or is `None` for a spectator. `lastRoll` is absent at the start of a turn (before any roll).
  */
case class PigState(
    scores: Map[String, Int],
    currentPlayer: String,
    turnScore: Int,
    lastRoll: Option[Int],
    winner: Option[String],
    viewerSeat: Option[String]
) extends GameState

object PigState {

  def of(game: Pig, viewerSeat: Option[Int]): PigState =
    PigState(
      scores = game.playerIds.indices.map(i => Pig.seatLabel(i) -> game.scores(i)).toMap,
      currentPlayer = Pig.seatLabel(game.currentSeat),
      turnScore = game.turnScore,
      lastRoll = game.lastRoll,
      winner = game.winner.map(Pig.seatLabel),
      viewerSeat = viewerSeat.map(Pig.seatLabel)
    )
}

/** One codebreaker guess in a Mastermind view: the guessed colors and its black/white peg feedback. */
case class GuessResult(pegs: List[String], black: Int, white: Int)

/** Serializable, per-viewer view of a Mastermind game.
  *
  * The `secret` is projected for the requesting viewer: the codemaker always sees their own code, and everyone
  * (codebreaker and spectators) sees it once the game is over — but never before, so guessing stays honest. Guesses and
  * their feedback are public. `currentPlayer` is `"codemaker"` while the code is unset and `"codebreaker"` afterwards.
  *
  * @param guesses the codebreaker's guesses with feedback, oldest first
  * @param secret the revealed code (color names), or `None` while it is still hidden from this viewer
  * @param guessesRemaining guesses the codebreaker has left before the codemaker wins by default
  * @param viewerRole the viewer's role (`"codemaker"`/`"codebreaker"`), or `None` for a spectator
  */
case class MastermindState(
    guesses: List[GuessResult],
    secret: Option[List[String]],
    currentPlayer: String,
    winner: Option[String],
    guessesRemaining: Int,
    viewerRole: Option[String]
) extends GameState

object MastermindState {

  /** Builds the view of `game` for the given viewer role (`None` = spectator). The secret is included only for the
    * codemaker or once the game has ended.
    */
  def of(game: Mastermind, viewerRole: Option[Role]): MastermindState = {
    val reveal = game.winner.isDefined || viewerRole.contains(Codemaker)
    MastermindState(
      guesses = game.guesses.map(a => GuessResult(a.guess.map(_.name).toList, a.feedback.black, a.feedback.white)),
      secret = if (reveal) game.secret.map(_.map(_.name).toList) else None,
      currentPlayer = game.currentPlayer.label,
      winner = game.winner.map(_.label),
      guessesRemaining = Mastermind.MaxGuesses - game.guesses.size,
      viewerRole = viewerRole.map(_.label)
    )
  }
}

/** Type class for converting an internal game model into a serializable GameState.
  *
  * Instances live in the companion object, which is always in implicit scope for `GameStateView[G, S]` searches, so
  * call sites need no imports beyond the types themselves.
  *
  * `viewer` identifies who the view is being rendered for: `Some(playerId)` for that player's own view, or `None` for a
  * public/spectator view. Full-information games (TicTacToe, ConnectFour) show everyone the same board and ignore it;
  * hidden-state games (e.g. Battleship) use it to reveal only what that viewer is allowed to see.
  */
trait GameStateView[G <: Game[_, _, _, _, _], S <: GameState] {
  def fromGame(game: G, viewer: Option[PlayerId]): S
}

object GameStateView {

  /** Type class instance for serializing a TicTacToe game into a GridGameState (same board for every viewer). */
  implicit val ticTacToeView: GameStateView[TicTacToe, GridGameState] =
    (game, _) => GridGameState.of(game.board, game.currentPlayer, game.gameStatus)

  /** Type class instance for serializing a ConnectFour game into a GridGameState (same board for every viewer). */
  implicit val connectFourView: GameStateView[ConnectFour, GridGameState] =
    (game, _) => GridGameState.of(game.board, game.currentPlayer, game.gameStatus)

  /** Type class instance for serializing a Battleship game, projected per viewer (fog-of-war for hidden boards). */
  implicit val battleshipView: GameStateView[Battleship, BattleshipState] =
    (game, viewer) => BattleshipState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Pig game (same state for every viewer; no hidden information). */
  implicit val pigView: GameStateView[Pig, PigState] =
    (game, viewer) => PigState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Mastermind game, projected per viewer (the secret is hidden until reveal). */
  implicit val mastermindView: GameStateView[Mastermind, MastermindState] =
    (game, viewer) => MastermindState.of(game, viewer.flatMap(game.playerFor))
}

/** Utility for converting internal game models to HTTP-serializable GameState representations using the GameStateView
  * type class.
  */
object GameStateConverters {

  /** Converts a concrete game instance into a corresponding GameState, rendered for `viewer`.
    *
    * This uses a type class instance of GameStateView[G, S], which knows how to convert a specific game type G (e.g.,
    * TicTacToe) into a specific GameState type S (e.g., GridGameState). `viewer` is `Some(playerId)` for that player's
    * own view or `None` for a public/spectator view; full-information games ignore it.
    */
  def serializeGame[G <: Game[_, _, _, _, _], S <: GameState](game: G, viewer: Option[PlayerId])(implicit
      view: GameStateView[G, S]
  ): S =
    view.fromGame(game, viewer)
}
