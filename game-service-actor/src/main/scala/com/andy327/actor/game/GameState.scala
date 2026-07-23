package com.andy327.actor.game

import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Seat}
import com.andy327.model.checkers.{Checkers, Color, Piece}
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.{Draw, Game, GameStatus, InProgress, Mark, PlayerId, Won}
import com.andy327.model.holdem.{Action, Card, HandResult, Street, TexasHoldEm}
import com.andy327.model.liarsdice.{Bid, LiarsDice, Reveal}
import com.andy327.model.mastermind.{Attempt, Codemaker, Mastermind, Peg, Role}
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.TicTacToe

/** Super-type for all serializable “view” representations of game state that can be sent to the client as part of an
  * HTTP response.
  */
sealed trait GameState

object GameState {

  /** The moves the viewer occupying `viewerSeat` may play right now: their own move set while it is their turn, and
    * `whenNotToAct` otherwise, since a viewer waiting on an opponent — or a spectator, holding no seat at all — has no
    * move to make. `moves` is by-name, so a view rendered for anyone but the player to act never pays to build it.
    * `M` is whatever shape the game represents its move set in: most views list their moves, while a range of sizings
    * (see [[BetSizing]]) gates its description here the same way.
    */
  private[game] def movesFor[P, M](viewerSeat: Option[P], currentPlayer: P, whenNotToAct: M)(moves: => M): M =
    if (viewerSeat.contains(currentPlayer)) moves else whenNotToAct

  private[game] def movesFor[P](viewerSeat: Option[P], currentPlayer: P)(
      moves: => List[MovePayload]
  ): List[MovePayload] =
    movesFor(viewerSeat, currentPlayer, List.empty[MovePayload])(moves)
}

/** View of any grid-based game state, shared by every game whose state is a board of cells each holding an optional
  * mark.
  *
  * Cells carry the `Mark` itself, so a consumer reasoning about the board has the domain value in hand; rendering a
  * mark to its symbol belongs to the encoder.
  *
  * @param legalMoves the moves the viewer may play right now: their own move set on their turn, empty otherwise. Carried
  *                   for consumers that choose or offer a move; not part of the wire format, so the encoder omits it.
  */
case class GridGameState(
    board: Vector[Vector[Option[Mark]]],
    currentPlayer: Mark,
    winner: Option[Mark],
    draw: Boolean,
    legalMoves: List[MovePayload]
) extends GameState

object GridGameState {

  /** Builds the view from any board of optional marks plus the game's current player, status, and the move set to
    * offer the viewer holding `viewerSeat`.
    */
  def of(
      board: Vector[Vector[Option[Mark]]],
      currentPlayer: Mark,
      status: GameStatus[Mark],
      viewerSeat: Option[Mark]
  )(legalMoves: => List[MovePayload]): GridGameState = {
    val winner = status match {
      case Won(mark) => Some(mark)
      case _         => None
    }
    GridGameState(
      board,
      currentPlayer,
      winner,
      status == Draw,
      GameState.movesFor(viewerSeat, currentPlayer)(legalMoves)
    )
  }
}

/** View of a Checkers game.
  *
  * The board is full information — every viewer sees the same pieces — and cells carry the `Piece` itself, so a
  * consumer reasoning about the position has the piece's color and crown in hand; the encoder renders them to the
  * `r`/`b` pawn and `R`/`B` king tokens.
  *
  * @param viewerSeat the color the viewer plays, or `None` for a spectator, letting a client tell a player which side
  *                   they are and whether it is their turn
  * @param legalMoves the moves the viewer may play right now: their own move set on their turn, empty otherwise.
  *                   Captures being mandatory, this lists only jumps whenever any exist. Not part of the wire format,
  *                   so the encoder omits it.
  */
case class CheckersState(
    board: Vector[Vector[Option[Piece]]],
    currentPlayer: Color,
    winner: Option[Color],
    viewerSeat: Option[Color],
    legalMoves: List[MovePayload]
) extends GameState

object CheckersState {

  /** Builds the view of `game` for the given viewer color (`None` = spectator). The board is identical for everyone. */
  def of(game: Checkers, viewerSeat: Option[Color]): CheckersState =
    CheckersState(
      board = game.board,
      currentPlayer = game.currentPlayer,
      winner = game.winner,
      viewerSeat = viewerSeat,
      legalMoves = GameState.movesFor(viewerSeat, game.currentPlayer)(
        game.legalMoves.map(move => MovePayload.CheckersMove(move.from, move.steps))
      )
    )
}

/** What one cell of a Battleship board looks like to a particular viewer.
  *
  * A resolved shot shows as [[BattleshipCell.Hit]] or [[BattleshipCell.Miss]] to everyone. The rest of the board
  * depends on whose waters it is: the viewer's own un-fired cells are [[BattleshipCell.Ship]] or
  * [[BattleshipCell.Water]], while an opponent's (or, for a spectator, anyone's) un-fired cell is
  * [[BattleshipCell.Unknown]] — the type has no way to say what lies beneath, which is what keeps a projected board
  * leak-free by construction.
  */
sealed trait BattleshipCell

object BattleshipCell {

  /** A fired cell that struck a ship. */
  case object Hit extends BattleshipCell

  /** A fired cell that found only water. */
  case object Miss extends BattleshipCell

  /** An un-fired cell of the viewer's own fleet. */
  case object Ship extends BattleshipCell

  /** An un-fired, empty cell of the viewer's own waters. */
  case object Water extends BattleshipCell

  /** An un-fired cell of waters the viewer cannot see into. */
  case object Unknown extends BattleshipCell
}

/** Per-viewer view of a Battleship game.
  *
  * `board1` and `board2` are each projected for the requesting viewer — see [[BattleshipCell]] for what each cell can
  * reveal. A viewer's legal shots are exactly the opponent cells still [[BattleshipCell.Unknown]] to them, so the view
  * derives every field, `legalMoves` included, from what its viewer is entitled to know.
  *
  * @param viewerSeat the seat the viewer occupies, or `None` for a spectator
  * @param legalMoves the moves the viewer may play right now: their own move set on their turn, empty otherwise. Not
  *                   part of the wire format, so the encoder omits it.
  */
case class BattleshipState(
    board1: Vector[Vector[BattleshipCell]],
    board2: Vector[Vector[BattleshipCell]],
    currentPlayer: Seat,
    winner: Option[Seat],
    viewerSeat: Option[Seat],
    legalMoves: List[MovePayload]
) extends GameState

object BattleshipState {

  /** Builds the view of `game` for the given viewer seat (`None` = spectator, who sees both boards fogged). */
  def of(game: Battleship, viewerSeat: Option[Seat]): BattleshipState =
    BattleshipState(
      board1 = project(game.board1, revealShips = viewerSeat.contains(Player1)),
      board2 = project(game.board2, revealShips = viewerSeat.contains(Player2)),
      currentPlayer = game.currentPlayer,
      winner = game.winner,
      viewerSeat = viewerSeat,
      legalMoves = GameState.movesFor(viewerSeat, game.currentPlayer)(
        game.legalMoves.map(fire => MovePayload.BattleshipMove(fire.target.row, fire.target.col))
      )
    )

  /** Projects one player's board for a viewer. When `revealShips` (the viewer's own board), un-hit ships show as
    * [[BattleshipCell.Ship]] and empty cells as [[BattleshipCell.Water]]; otherwise every un-fired cell is
    * [[BattleshipCell.Unknown]], hiding ship positions until hit.
    */
  private def project(b: PlayerBoard, revealShips: Boolean): Vector[Vector[BattleshipCell]] = {
    val shipCells = b.shipCells
    Vector.tabulate(Battleship.Size, Battleship.Size) { (r, c) =>
      val coord = Coord(r, c)
      val isShip = shipCells.contains(coord)
      if (b.shots.contains(coord)) if (isShip) BattleshipCell.Hit else BattleshipCell.Miss
      else if (revealShips) if (isShip) BattleshipCell.Ship else BattleshipCell.Water
      else BattleshipCell.Unknown
    }
  }
}

/** View of a Pig game. Pig hides nothing, so every viewer sees the same state bar their own `viewerSeat`.
  *
  * Seats are zero-based indices — the token the model uses as its player type — and the encoder applies the
  * `"P1"`/`"P2"` labels. `scores` is indexed in seat order, so `scores(0)` belongs to the seat that moved first.
  *
  * @param lastRoll the die value from the most recent roll, absent at the start of a turn
  * @param legalMoves the moves the viewer may play right now: their own move set on their turn, empty otherwise. Not
  *                   part of the wire format, so the encoder omits it.
  */
case class PigState(
    scores: Vector[Int],
    currentPlayer: Int,
    turnScore: Int,
    lastRoll: Option[Int],
    winner: Option[Int],
    viewerSeat: Option[Int],
    legalMoves: List[MovePayload]
) extends GameState

object PigState {

  /** Pig's move set is the same on every turn: a player may always roll, and may always hold — banking nothing when
    * their turn score is zero is a legal pass. The die a `Roll` carries is supplied by `PigModule`, so these payloads
    * name the action alone and the module rolls for it.
    */
  private val TurnMoves: List[MovePayload] =
    List(MovePayload.PigAction("roll"), MovePayload.PigAction("hold"))

  def of(game: Pig, viewerSeat: Option[Int]): PigState =
    PigState(
      scores = game.scores,
      currentPlayer = game.currentSeat,
      turnScore = game.turnScore,
      lastRoll = game.lastRoll,
      winner = game.winner,
      viewerSeat = viewerSeat,
      legalMoves = GameState.movesFor(viewerSeat, game.currentSeat)(
        if (game.gameStatus == InProgress) TurnMoves else Nil
      )
    )
}

/** Per-viewer view of a Mastermind game.
  *
  * The `secret` is projected for the requesting viewer: the codemaker always sees their own code, and everyone
  * (codebreaker and spectators) sees it once the game is over — but never before, so guessing stays honest. Guesses
  * and their feedback are public, carried as the model's own `Attempt`s; rendering pegs and roles to their names
  * belongs to the encoder. `currentPlayer` is `Codemaker` while the code is unset and `Codebreaker` afterwards.
  *
  * The view carries no `legalMoves`: Mastermind's move space is the constant `Peg.all ^ Mastermind.CodeLength` —
  * every code of `CodeLength` pegs, for the code-setting and every guess alike — so there is nothing per-state to
  * enumerate or describe. A consumer choosing a move may act exactly when `viewerRole` matches `currentPlayer` and
  * `winner` is empty, and builds its code from those two model constants.
  *
  * @param guesses the codebreaker's guesses with feedback, oldest first
  * @param secret the code, or `None` while it is still hidden from this viewer
  * @param guessesRemaining guesses the codebreaker has left before the codemaker wins by default
  * @param viewerRole the viewer's role, or `None` for a spectator
  */
case class MastermindState(
    guesses: List[Attempt],
    secret: Option[Vector[Peg]],
    currentPlayer: Role,
    winner: Option[Role],
    guessesRemaining: Int,
    viewerRole: Option[Role]
) extends GameState

object MastermindState {

  /** Builds the view of `game` for the given viewer role (`None` = spectator). The secret is included only for the
    * codemaker or once the game has ended.
    */
  def of(game: Mastermind, viewerRole: Option[Role]): MastermindState = {
    val reveal = game.winner.isDefined || viewerRole.contains(Codemaker)
    MastermindState(
      guesses = game.guesses,
      secret = if (reveal) game.secret else None,
      currentPlayer = game.currentPlayer,
      winner = game.winner,
      guessesRemaining = Mastermind.MaxGuesses - game.guesses.size,
      viewerRole = viewerRole
    )
  }
}

/** Per-viewer view of a Liar's Dice game.
  *
  * The viewer sees their own current dice and every seat's dice count, but never another seat's current dice — hidden
  * information stays hidden. `lastReveal` is the exception: it carries the model's own `Reveal`, capturing all dice at
  * the moment of the last challenge, which is public once the cups come up. Seats are zero-based indices and the bid
  * and reveal are the model's own types; the encoder applies the `"P1"`/`"P2"` labels.
  *
  * @param dice the viewer's own current dice (empty if eliminated), or `None` for a spectator
  * @param diceCounts dice remaining per seat, indexed in seat order
  * @param currentBid the standing bid, or `None` at the start of a round
  * @param lastReveal the most recently resolved challenge, or `None` before any challenge
  * @param legalMoves the moves the viewer may play right now: their own move set on their turn, empty otherwise —
  *                   every useful raise (see `LiarsDice.legalBids` for the quantity cap) plus the challenge whenever a
  *                   standing bid exists. Not part of the wire format, so the encoder omits it.
  */
case class LiarsDiceState(
    dice: Option[Vector[Int]],
    diceCounts: Vector[Int],
    currentBid: Option[Bid],
    currentPlayer: Int,
    winner: Option[Int],
    viewerSeat: Option[Int],
    lastReveal: Option[Reveal],
    legalMoves: List[MovePayload]
) extends GameState

object LiarsDiceState {

  /** Builds the view of `game` for the given viewer seat (`None` = spectator, who sees dice counts but no hand). */
  def of(game: LiarsDice, viewerSeat: Option[Int]): LiarsDiceState =
    LiarsDiceState(
      dice = viewerSeat.map(game.dice),
      diceCounts = game.playerIds.indices.map(game.diceCount).toVector,
      currentBid = game.standing.map(_.bid),
      currentPlayer = game.currentSeat,
      winner = game.winner,
      viewerSeat = viewerSeat,
      lastReveal = game.lastReveal,
      legalMoves = GameState.movesFor(viewerSeat, game.currentSeat) {
        // the challenge payload names the action alone: the fresh dice a challenge deals are rolled by the module
        val challenge =
          if (game.standing.isDefined) List(MovePayload.LiarsDiceAction("challenge", None, None)) else Nil
        game.legalBids.map(bid => MovePayload.LiarsDiceAction("bid", Some(bid.quantity), bid.face)) ++ challenge
      }
    )
}

/** One seat's public state in a Texas Hold 'Em view: its chips, what it has committed this hand and this street, and
  * whether it has folded or is all-in. Hole cards are never here — a viewer sees only their own, in [[HoldEmState]].
  * The seat's index is its position in the view's seat list.
  */
case class HoldEmSeat(stack: Int, committed: Int, bet: Int, folded: Boolean, allIn: Boolean)

/** The range of bet or raise sizings open to a Texas Hold 'Em viewer on their turn.
  *
  * `action` names the move as the wire knows it — `"bet"` when nothing stands on the street, `"raise"` against a
  * standing bet — and every street-contribution total from `min` to `max` is playable: `max` is the seat's all-in,
  * and when the stack cannot reach the normal minimum the all-in alone remains, leaving `min == max`. The range is
  * described rather than enumerated because it is one: sizings are contiguous chip totals, not discrete alternatives.
  */
case class BetSizing(action: String, min: Int, max: Int)

/** Per-viewer view of a Texas Hold 'Em game.
  *
  * The viewer sees their own hole cards and every seat's public chips/bets, but never another seat's hole cards until
  * a showdown, which surfaces through `handResult` — the model's own reveal, carried as-is. `board` holds only the
  * community cards revealed on the current street. Seats are zero-based indices and cards are the model's own; the
  * encoder applies the `"P1"`/`"P2"` labels and the compact card text.
  *
  * @param seats every seat's public state, in seat order
  * @param holeCards the viewer's own two hole cards, or `None` for a spectator
  * @param board the community cards revealed so far
  * @param button the seat holding the dealer button
  * @param currentBet the amount to match on the current street
  * @param minRaise the smallest total a bet or raise may commit this street
  * @param pot the total chips committed to the pot this hand
  * @param toCall the chips the viewer must put in to call, or 0 for a spectator
  * @param handResult the most recently finished hand's public reveal, or `None` before any hand ends
  * @param legalMoves the chip-free moves the viewer may play right now: their own move set on their turn, empty
  *                   otherwise. A bet or raise is a range, not a move, and lives in `betSizing`. Not part of the wire
  *                   format, so the encoder omits it.
  * @param betSizing the sizings open to the viewer on their turn, `None` otherwise or when no sizing action is open —
  *                  see [[BetSizing]]. Not part of the wire format, so the encoder omits it.
  */
case class HoldEmState(
    seats: List[HoldEmSeat],
    holeCards: Option[List[Card]],
    board: List[Card],
    button: Int,
    currentPlayer: Int,
    currentBet: Int,
    minRaise: Int,
    pot: Int,
    toCall: Int,
    street: Street,
    winner: Option[Int],
    viewerSeat: Option[Int],
    handResult: Option[HandResult],
    legalMoves: List[MovePayload],
    betSizing: Option[BetSizing]
) extends GameState

object HoldEmState {

  /** Builds the view of `game` for the given viewer seat (`None` = spectator, who sees no hole cards). */
  def of(game: TexasHoldEm, viewerSeat: Option[Int]): HoldEmState =
    HoldEmState(
      seats = game.playerIds.indices.toList.map { i =>
        HoldEmSeat(game.stacks(i), game.committed(i), game.streetContrib(i), game.folded(i), game.allIn(i))
      },
      holeCards = viewerSeat.map(game.holeCards),
      board = game.board.take(game.street.revealed),
      button = game.button,
      currentPlayer = game.toAct,
      currentBet = game.currentBet,
      minRaise = if (game.currentBet == 0) TexasHoldEm.BigBlind else game.currentBet + game.lastRaiseSize,
      pot = game.pot,
      toCall = viewerSeat.map(game.toCall).getOrElse(0),
      street = game.street,
      winner = game.winner,
      viewerSeat = viewerSeat,
      handResult = game.handResult,
      legalMoves = GameState.movesFor(viewerSeat, game.toAct)(
        game.legalActions.collect {
          case Action.Fold  => MovePayload.HoldEmAction("fold", None)
          case Action.Check => MovePayload.HoldEmAction("check", None)
          case Action.Call  => MovePayload.HoldEmAction("call", None)
        }
      ),
      betSizing = GameState.movesFor(viewerSeat, game.toAct, Option.empty[BetSizing])(
        game.betBounds.map { case (min, max) =>
          BetSizing(if (game.currentBet == 0) "bet" else "raise", min, max)
        }
      )
    )
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
    (game, viewer) =>
      GridGameState.of(game.board, game.currentPlayer, game.gameStatus, viewer.flatMap(game.playerFor))(
        game.legalMoves.map(loc => MovePayload.TicTacToeMove(loc.row, loc.col))
      )

  /** Type class instance for serializing a ConnectFour game into a GridGameState (same board for every viewer). */
  implicit val connectFourView: GameStateView[ConnectFour, GridGameState] =
    (game, viewer) =>
      GridGameState.of(game.board, game.currentPlayer, game.gameStatus, viewer.flatMap(game.playerFor))(
        game.legalMoves.map(drop => MovePayload.ConnectFourMove(drop.col))
      )

  /** Type class instance for serializing a Battleship game, projected per viewer (fog-of-war for hidden boards). */
  implicit val battleshipView: GameStateView[Battleship, BattleshipState] =
    (game, viewer) => BattleshipState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Pig game (same state for every viewer; no hidden information). */
  implicit val pigView: GameStateView[Pig, PigState] =
    (game, viewer) => PigState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Mastermind game, projected per viewer (the secret is hidden until reveal). */
  implicit val mastermindView: GameStateView[Mastermind, MastermindState] =
    (game, viewer) => MastermindState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Liar's Dice game, projected per viewer (only the viewer's own dice show). */
  implicit val liarsDiceView: GameStateView[LiarsDice, LiarsDiceState] =
    (game, viewer) => LiarsDiceState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Texas Hold 'Em game, projected per viewer (only the viewer's hole cards). */
  implicit val texasHoldEmView: GameStateView[TexasHoldEm, HoldEmState] =
    (game, viewer) => HoldEmState.of(game, viewer.flatMap(game.playerFor))

  /** Type class instance for serializing a Checkers game. The board is the same for every viewer; the view is tagged
    * with the viewer's own color so the client can show which side they are and whose turn it is.
    */
  implicit val checkersView: GameStateView[Checkers, CheckersState] =
    (game, viewer) => CheckersState.of(game, viewer.flatMap(game.playerFor))
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
