package com.andy327.actor.game

import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Seat}
import com.andy327.model.checkers.{Checkers, Color, Piece}
import com.andy327.model.connectfour.ConnectFour
import com.andy327.model.core.{Draw, Game, GameStatus, InProgress, Mark, PlayerId, Won}
import com.andy327.model.holdem.TexasHoldEm
import com.andy327.model.liarsdice.{Bid, LiarsDice}
import com.andy327.model.mastermind.{Codemaker, Mastermind, Role}
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.TicTacToe

/** Super-type for all serializable “view” representations of game state that can be sent to the client as part of an
  * HTTP response.
  */
sealed trait GameState

/** View of any grid-based game state, shared by every game whose state is a board of cells each holding an optional
  * mark.
  *
  * Cells carry the `Mark` itself, so a consumer reasoning about the board has the domain value in hand; rendering a
  * mark to its symbol belongs to the encoder.
  *
  * @param legalMoves the moves `currentPlayer` may play right now, empty once the game is over. Carried for consumers
  *                   that choose or offer a move; not part of the wire format, so the encoder omits it.
  */
case class GridGameState(
    board: Vector[Vector[Option[Mark]]],
    currentPlayer: Mark,
    winner: Option[Mark],
    draw: Boolean,
    legalMoves: List[MovePayload]
) extends GameState

object GridGameState {

  /** Builds the view from any board of optional marks plus the game's current player, status, and available moves. */
  def of(
      board: Vector[Vector[Option[Mark]]],
      currentPlayer: Mark,
      status: GameStatus[Mark],
      legalMoves: List[MovePayload]
  ): GridGameState = {
    val winner = status match {
      case Won(mark) => Some(mark)
      case _         => None
    }
    GridGameState(board, currentPlayer, winner, status == Draw, legalMoves)
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
  * @param legalMoves the moves `currentPlayer` may play right now, empty once the game is over. Captures being
  *                   mandatory, this lists only jumps whenever any exist. Not part of the wire format, so the encoder
  *                   omits it.
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
      legalMoves = game.legalMoves.map(move => MovePayload.CheckersMove(move.from, move.steps))
    )
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

/** View of a Pig game. Pig hides nothing, so every viewer sees the same state bar their own `viewerSeat`.
  *
  * Seats are zero-based indices — the token the model uses as its player type — and the encoder applies the
  * `"P1"`/`"P2"` labels. `scores` is indexed in seat order, so `scores(0)` belongs to the seat that moved first.
  *
  * @param lastRoll the die value from the most recent roll, absent at the start of a turn
  * @param legalMoves the moves `currentPlayer` may play right now, empty once the game is over. Not part of the wire
  *                   format, so the encoder omits it.
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
      legalMoves = if (game.gameStatus == InProgress) TurnMoves else Nil
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

/** One bid in a Liar's Dice view: a quantity plus either a numbered face (2–6) or `None` for a wild "ones" bid. */
case class BidView(quantity: Int, face: Option[Int])

/** The public reveal of the most recently resolved challenge in a Liar's Dice view.
  *
  * Every seat's dice are exposed here (keyed by seat label) because a challenge is the one moment all cups come up;
  * this snapshot is safe to show everyone and persists into the next round for reconnecting clients.
  *
  * @param bid the bid that was challenged
  * @param count the true number of dice counting toward `bid` (its face plus wild ones)
  * @param dice every seat's dice at the challenge, keyed by seat label ("P1", "P2", …)
  * @param challenger the seat that called "Liar"
  * @param bidder the seat whose bid was challenged
  * @param loser the seat that lost dice
  * @param diceLost how many dice `loser` lost (0 when a challenger meets the bid exactly)
  */
case class RevealView(
    bid: BidView,
    count: Int,
    dice: Map[String, List[Int]],
    challenger: String,
    bidder: String,
    loser: String,
    diceLost: Int
)

/** Serializable, per-viewer view of a Liar's Dice game.
  *
  * The viewer sees their own current dice and every seat's dice count, but never another seat's current dice — hidden
  * information stays hidden. `lastReveal` is the exception: it captures all dice at the moment of the last challenge,
  * which is public once the cups come up.
  *
  * @param dice the viewer's own current dice, or `None` for a spectator
  * @param diceCounts dice remaining per seat, keyed by seat label ("P1", "P2", …)
  * @param currentBid the standing bid, or `None` at the start of a round
  * @param currentPlayer the seat label whose turn it is
  * @param winner the winning seat label once the game is over, otherwise `None`
  * @param viewerSeat the viewer's seat label, or `None` for a spectator
  * @param lastReveal the most recently resolved challenge, or `None` before any challenge
  */
case class LiarsDiceState(
    dice: Option[List[Int]],
    diceCounts: Map[String, Int],
    currentBid: Option[BidView],
    currentPlayer: String,
    winner: Option[String],
    viewerSeat: Option[String],
    lastReveal: Option[RevealView]
) extends GameState

object LiarsDiceState {

  /** Builds the view of `game` for the given viewer seat (`None` = spectator, who sees dice counts but no hand). */
  def of(game: LiarsDice, viewerSeat: Option[Int]): LiarsDiceState = {
    def label(seat: Int): String = LiarsDice.seatLabel(seat)
    def bidView(bid: Bid): BidView = BidView(bid.quantity, bid.face)
    LiarsDiceState(
      dice = viewerSeat.map(seat => game.dice(seat).toList),
      diceCounts = game.playerIds.indices.map(i => label(i) -> game.diceCount(i)).toMap,
      currentBid = game.standing.map(sb => bidView(sb.bid)),
      currentPlayer = label(game.currentSeat),
      winner = game.winner.map(label),
      viewerSeat = viewerSeat.map(label),
      lastReveal = game.lastReveal.map { r =>
        RevealView(
          bid = bidView(r.bid),
          count = r.count,
          dice = game.playerIds.indices.map(i => label(i) -> r.allDice(i).toList).toMap,
          challenger = label(r.challengerSeat),
          bidder = label(r.bidderSeat),
          loser = label(r.loserSeat),
          diceLost = r.diceLost
        )
      }
    )
  }
}

/** One seat's public state in a Texas Hold 'Em view: its chips, what it has committed this hand and this street, and
  * whether it has folded or is all-in. Hole cards are never here — a viewer sees only their own, in [[HoldEmState]].
  */
case class HoldEmSeat(seat: String, stack: Int, committed: Int, bet: Int, folded: Boolean, allIn: Boolean)

/** One pot awarded in a Texas Hold 'Em showdown view: chips, the winning seat labels, and the hand description (absent
  * when the pot was taken uncontested).
  */
case class HoldEmPotAward(amount: Int, winners: List[String], description: Option[String])

/** The public reveal of the most recently finished Texas Hold 'Em hand.
  *
  * Every seat that reached the showdown exposes its hole cards here (keyed by seat label); folded hands stay hidden.
  * Safe to show everyone and retained into the next hand for reconnecting clients, mirroring Liar's Dice's reveal.
  *
  * @param board the community cards that were face-up when the hand ended
  * @param shownHands the hole cards shown at the showdown, keyed by seat label ("P1", "P2", …)
  * @param awards the pots awarded, main pot first
  */
case class HoldEmHandResult(board: List[String], shownHands: Map[String, List[String]], awards: List[HoldEmPotAward])

/** Serializable, per-viewer view of a Texas Hold 'Em game.
  *
  * The viewer sees their own hole cards and every seat's public chips/bets, but never another seat's hole cards until a
  * showdown, which surfaces through `handResult`. `board` holds only the community cards revealed on the current street.
  * Cards are rendered as their compact text form ("As", "Td"); `street` is the street name.
  *
  * @param seats every seat's public state, in seat order
  * @param holeCards the viewer's own two hole cards, or `None` for a spectator
  * @param board the community cards revealed so far
  * @param button the seat label holding the dealer button
  * @param currentPlayer the seat label whose turn it is
  * @param currentBet the amount to match on the current street
  * @param minRaise the smallest total a bet or raise may commit this street
  * @param pot the total chips committed to the pot this hand
  * @param toCall the chips the viewer must put in to call, or 0 for a spectator
  * @param street the current street ("PreFlop", "Flop", "Turn", "River")
  * @param winner the winning seat label once the sit-and-go is over, otherwise `None`
  * @param viewerSeat the viewer's seat label, or `None` for a spectator
  * @param handResult the most recently finished hand's public reveal, or `None` before any hand ends
  */
case class HoldEmState(
    seats: List[HoldEmSeat],
    holeCards: Option[List[String]],
    board: List[String],
    button: String,
    currentPlayer: String,
    currentBet: Int,
    minRaise: Int,
    pot: Int,
    toCall: Int,
    street: String,
    winner: Option[String],
    viewerSeat: Option[String],
    handResult: Option[HoldEmHandResult]
) extends GameState

object HoldEmState {

  /** Builds the view of `game` for the given viewer seat (`None` = spectator, who sees no hole cards). */
  def of(game: TexasHoldEm, viewerSeat: Option[Int]): HoldEmState = {
    def label(seat: Int): String = TexasHoldEm.seatLabel(seat)
    val minRaise = if (game.currentBet == 0) TexasHoldEm.BigBlind else game.currentBet + game.lastRaiseSize
    HoldEmState(
      seats = game.playerIds.indices.toList.map { i =>
        HoldEmSeat(label(i), game.stacks(i), game.committed(i), game.streetContrib(i), game.folded(i), game.allIn(i))
      },
      holeCards = viewerSeat.map(seat => game.holeCards(seat).map(_.toString)),
      board = game.board.take(game.street.revealed).map(_.toString),
      button = label(game.button),
      currentPlayer = label(game.toAct),
      currentBet = game.currentBet,
      minRaise = minRaise,
      pot = game.pot,
      toCall = viewerSeat.map(game.toCall).getOrElse(0),
      street = game.street.toString,
      winner = game.winner.map(label),
      viewerSeat = viewerSeat.map(label),
      handResult = game.handResult.map { r =>
        HoldEmHandResult(
          board = r.board.map(_.toString),
          shownHands = r.shownHands.map { case (seat, cs) => label(seat) -> cs.map(_.toString) },
          awards = r.awards.map(a => HoldEmPotAward(a.amount, a.winners.map(label), a.description))
        )
      }
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
    (game, _) =>
      GridGameState.of(
        game.board,
        game.currentPlayer,
        game.gameStatus,
        game.legalMoves.map(loc => MovePayload.TicTacToeMove(loc.row, loc.col))
      )

  /** Type class instance for serializing a ConnectFour game into a GridGameState (same board for every viewer). */
  implicit val connectFourView: GameStateView[ConnectFour, GridGameState] =
    (game, _) =>
      GridGameState.of(
        game.board,
        game.currentPlayer,
        game.gameStatus,
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
