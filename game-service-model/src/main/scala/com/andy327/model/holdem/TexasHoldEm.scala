package com.andy327.model.holdem

import com.andy327.model.core.GameError.{GameOver, InvalidPlayer, InvalidTurn}
import com.andy327.model.core.{Game, GameError, GameStatus, InProgress, PlayerId, Won}

/** A betting street. `revealed` is the number of community cards that are face-up on that street. */
sealed trait Street {
  def revealed: Int
}

object Street {
  case object PreFlop extends Street { val revealed: Int = 0 }
  case object Flop extends Street { val revealed: Int = 3 }
  case object Turn extends Street { val revealed: Int = 4 }
  case object River extends Street { val revealed: Int = 5 }

  /** The street that follows `street`, or `None` after the river (the last betting street). */
  def next(street: Street): Option[Street] = street match {
    case PreFlop => Some(Flop)
    case Flop    => Some(Turn)
    case Turn    => Some(River)
    case River   => None
  }
}

/** A player action on their turn. `Bet`/`Raise` carry the chip target as a total street contribution, not a delta. */
sealed trait Action
object Action {
  case object Fold extends Action
  case object Check extends Action
  case object Call extends Action

  /** Open the betting for `amount` chips (only legal when there is no bet yet on the street). */
  final case class Bet(amount: Int) extends Action

  /** Raise so the acting player's total contribution this street becomes `toAmount`. */
  final case class Raise(toAmount: Int) extends Action
}

/** A Hold 'Em move: a player [[Action]] plus a freshly shuffled deck the pure model deals from only if this action
  * starts the next hand. The deck rides along on every action because any action can end a hand (a fold down to one
  * player, or a call that closes the river); it is kept server-side (rolled in the actor layer) so the model stays
  * pure, mirroring how Liar's Dice injects its re-roll pool. The move-log encoder records only `action`, never `deck`.
  */
final case class HoldEmMove(action: Action, deck: List[Card])

/** One pot awarded at the end of a hand: `amount` chips split among `winners`, with the winning `description` at a
  * showdown (or `None` when everyone else folded and the pot was taken uncontested).
  */
final case class PotAward(amount: Int, winners: List[Int], description: Option[String])

/** The public record of the hand that just finished — the one moment hole cards may come up.
  *
  * Retained in the game state so all viewers, including reconnecting clients, can see how the last hand ended even
  * after the next hand has been dealt.
  *
  * @param board the community cards that were face-up when the hand ended (all five at a showdown)
  * @param shownHands the hole cards of each seat that reached the showdown, keyed by seat; empty for an uncontested win
  * @param awards the pots awarded, main pot first
  */
final case class HandResult(board: List[Card], shownHands: Map[Int, List[Card]], awards: List[PotAward])

object TexasHoldEm {

  /** Chips each player starts a sit-and-go with. */
  val StartingStack: Int = 1000

  /** The small blind, posted by the seat to the left of the button (the button itself when heads-up). */
  val SmallBlind: Int = 5

  /** The big blind, posted by the next seat along; also the minimum opening bet. */
  val BigBlind: Int = 10

  /** Hole cards dealt to each seat still in the sit-and-go. */
  val HoleCards: Int = 2

  /** Creates a fresh sit-and-go: equal starting stacks, the button just before seat 0 (so the first hand's button is
    * seat 0), and the first hand dealt and blinded from `deck`. `deck` is a full 52-card shuffle produced server-side.
    */
  def newGame(playerIds: Seq[PlayerId], deck: List[Card]): TexasHoldEm = {
    val n = playerIds.size
    val base = TexasHoldEm(
      playerIds = playerIds.toVector,
      stacks = Vector.fill(n)(StartingStack),
      button = n - 1,
      holeCards = Vector.fill(n)(Nil),
      board = Nil,
      deck = Nil,
      street = Street.PreFlop,
      toAct = 0,
      streetContrib = Vector.fill(n)(0),
      committed = Vector.fill(n)(0),
      currentBet = 0,
      lastRaiseSize = BigBlind,
      hasActed = Vector.fill(n)(false),
      folded = Vector.fill(n)(false),
      allIn = Vector.fill(n)(false),
      handResult = None,
      winner = None,
      moveCount = 0
    )
    base.startHand(deck)
  }
}

/** A No-Limit Texas Hold 'Em sit-and-go for 2–6 players.
  *
  * A "game" runs many hands until one seat holds all the chips — that seat is the single `Won` winner, so the game
  * fits `GameStatus[Int]` with no draw and no multi-winner status. Individual hands are internal rounds: chips move
  * between seats (including split and side pots) but only the last-player-standing outcome ever surfaces as the game's
  * status. This mirrors Liar's Dice, whose game is a sequence of rounds ending when one player remains.
  *
  * The model is pure: cards are shuffled server-side and supplied to it — the first hand via [[TexasHoldEm.newGame]],
  * each later hand via the [[HoldEmMove]] deck that the action starting it carries. Community cards are dealt up front
  * and hidden at the view edge (only [[Street.revealed]]-many are shown); opponents' hole cards are revealed only
  * through [[handResult]] at a showdown.
  *
  * @param playerIds seat order; the seat index into this vector is the game's player token
  * @param stacks chips held by each seat; a seat at zero has been eliminated from the sit-and-go
  * @param button the dealer-button seat for the current hand
  * @param holeCards each seat's two hole cards for the current hand (empty for an eliminated seat)
  * @param board the current hand's five community cards, dealt up front and revealed a street at a time
  * @param deck the current hand's undealt cards; a leave that must start a new hand deals from this unseen remainder
  * @param street the current betting street
  * @param toAct the seat whose turn it is to act
  * @param streetContrib chips each seat has put in on the current street (reset each street)
  * @param committed chips each seat has put in over the whole hand (drives pot and side-pot construction)
  * @param currentBet the highest street contribution — the amount a seat must match to stay in
  * @param lastRaiseSize the size of the last bet or raise, the minimum a further raise must add
  * @param hasActed whether each seat has acted on the current street (reset each street)
  * @param folded whether each seat has folded (or been eliminated) for the current hand
  * @param allIn whether each seat is all-in for the current hand
  * @param handResult the public record of the most recently finished hand, or None before any hand ends
  * @param winner the winning seat once one player holds all the chips, otherwise None
  * @param moveCount total player actions applied so far; survives serialization for the history log
  */
final case class TexasHoldEm(
    playerIds: Vector[PlayerId],
    stacks: Vector[Int],
    button: Int,
    holeCards: Vector[List[Card]],
    board: List[Card],
    deck: List[Card],
    street: Street,
    toAct: Int,
    streetContrib: Vector[Int],
    committed: Vector[Int],
    currentBet: Int,
    lastRaiseSize: Int,
    hasActed: Vector[Boolean],
    folded: Vector[Boolean],
    allIn: Vector[Boolean],
    handResult: Option[HandResult],
    winner: Option[Int],
    moveCount: Int
) extends Game[HoldEmMove, TexasHoldEm, Int, GameStatus[Int], GameError] {

  import Action._
  import TexasHoldEm.{BigBlind, HoleCards, SmallBlind}

  private val n: Int = playerIds.size

  def currentState: TexasHoldEm = this
  def currentPlayer: Int = toAct
  def gameStatus: GameStatus[Int] = winner.map(Won(_)).getOrElse(InProgress)
  def players: List[PlayerId] = playerIds.toList

  /** Resolves a platform player ID to its zero-based seat index, or `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Int] = {
    val i = playerIds.indexOf(playerId)
    if (i >= 0) Some(i) else None
  }

  /** Chips in the pot (and side pots) for the current hand — the sum of every seat's committed chips. */
  def pot: Int = committed.sum

  /** The amount `seat` must put in to call the current bet. */
  def toCall(seat: Int): Int = math.max(0, currentBet - streetContrib(seat))

  // --- seat predicates -------------------------------------------------------------------------------------------

  private def inHand(seat: Int): Boolean = !folded(seat)
  private def contestable(seat: Int): Boolean = inHand(seat) && !allIn(seat)
  private def contestableCount: Int = (0 until n).count(contestable)

  /** A seat still owes an action this street if it can act and has either not acted or not yet matched the bet. */
  private def needsAction(seat: Int): Boolean =
    contestable(seat) && (!hasActed(seat) || streetContrib(seat) < currentBet)

  private def roundComplete: Boolean = !(0 until n).exists(needsAction)

  /** All seats in clockwise order strictly after `from` (the n-1 other seats). */
  private def seatsAfter(from: Int): Seq[Int] = (1 until n).map(i => (from + i) % n)

  /** All n seats in clockwise order starting just after `from` and ending at `from`. */
  private def orderFrom(from: Int): Seq[Int] = (1 to n).map(i => (from + i) % n)

  private def nextSeat(from: Int)(pred: Int => Boolean): Option[Int] = seatsAfter(from).find(pred)

  // --- applying a move -------------------------------------------------------------------------------------------

  /** Applies a player action, then settles the table: advances the turn, opens the next street, runs a showdown, and
    * deals the next hand as needed, all in one step. Rejects an out-of-turn player or a finished game.
    */
  def play(player: Int, move: HoldEmMove): Either[GameError, TexasHoldEm] =
    if (winner.isDefined) Left(GameOver)
    else if (player != toAct) Left(InvalidTurn)
    else applyAction(player, move.action).map(_.copy(moveCount = moveCount + 1).settle(move.deck))

  /** Validates and applies a single action for `seat`, updating that seat's chips and the street's betting state. */
  private def applyAction(seat: Int, action: Action): Either[GameError, TexasHoldEm] = action match {
    case Fold =>
      Right(copy(folded = folded.updated(seat, true), hasActed = hasActed.updated(seat, true)))

    case Check =>
      if (streetContrib(seat) < currentBet) Left(CannotCheck)
      else Right(markActed(seat))

    case Call =>
      val pay = math.min(toCall(seat), stacks(seat))
      Right(putIn(seat, pay).markActed(seat))

    case Bet(amount) =>
      if (currentBet > 0) Left(BetNotAllowed)
      else if (amount > stacks(seat)) Left(InsufficientChips)
      else if (amount < BigBlind && amount < stacks(seat)) Left(BetTooSmall)
      else Right(putIn(seat, amount).copy(currentBet = amount, lastRaiseSize = amount).markActed(seat))

    case Raise(toAmount) =>
      val additional = toAmount - streetContrib(seat)
      val increment = toAmount - currentBet
      if (currentBet == 0) Left(RaiseNotAllowed)
      else if (increment <= 0) Left(RaiseTooSmall)
      else if (additional > stacks(seat)) Left(InsufficientChips)
      else if (increment < lastRaiseSize && additional < stacks(seat)) Left(RaiseTooSmall)
      else {
        val newLastRaise = if (increment >= lastRaiseSize) increment else lastRaiseSize
        Right(putIn(seat, additional).copy(currentBet = toAmount, lastRaiseSize = newLastRaise).markActed(seat))
      }
  }

  /** Moves `amount` chips from `seat`'s stack into the pot, flagging the seat all-in if it empties their stack. */
  private def putIn(seat: Int, amount: Int): TexasHoldEm = {
    val remaining = stacks(seat) - amount
    copy(
      stacks = stacks.updated(seat, remaining),
      streetContrib = streetContrib.updated(seat, streetContrib(seat) + amount),
      committed = committed.updated(seat, committed(seat) + amount),
      allIn = if (remaining == 0) allIn.updated(seat, true) else allIn
    )
  }

  private def markActed(seat: Int): TexasHoldEm = copy(hasActed = hasActed.updated(seat, true))

  /** Advances the table after an action or a leave: continues the street, opens the next one, or ends the hand.
    *
    * `handDeck` supplies the cards for the next hand should this step start one — the injected shuffle for a normal
    * move, or the current hand's unseen remainder for a leave.
    */
  private def settle(handDeck: List[Card]): TexasHoldEm =
    if ((0 until n).count(inHand) == 1) concludeHand(showdown = false, handDeck)
    else if (roundComplete)
      if (street == Street.River || contestableCount <= 1) concludeHand(showdown = true, handDeck)
      else advanceStreet
    else {
      // the actor (or leaver) has acted; pass the turn to the next seat that still owes an action, unless the current
      // seat still does (a leave by a player who was not on the clock leaves the turn where it was)
      val next = if (needsAction(toAct)) toAct else nextSeat(toAct)(needsAction).get
      copy(toAct = next)
    }

  /** Opens the next street: reveals its community cards (via the view), clears the street's betting, and gives the turn
    * to the first seat left of the button that can still act.
    */
  private def advanceStreet: TexasHoldEm = {
    val fresh = copy(
      street = Street.next(street).get,
      streetContrib = Vector.fill(n)(0),
      currentBet = 0,
      lastRaiseSize = BigBlind,
      hasActed = Vector.fill(n)(false)
    )
    fresh.copy(toAct = orderFrom(button).find(fresh.contestable).get)
  }

  // --- ending a hand ---------------------------------------------------------------------------------------------

  /** Awards the pot(s), records the [[HandResult]], then either ends the sit-and-go or deals the next hand. */
  private def concludeHand(showdown: Boolean, handDeck: List[Card]): TexasHoldEm = {
    val (awards, newStacks) = computeAwards(showdown)
    val shown =
      if (showdown) (0 until n).filter(inHand).map(s => s -> holeCards(s)).toMap else Map.empty[Int, List[Card]]
    val revealed = if (showdown) board else board.take(street.revealed)
    val ended = copy(stacks = newStacks, handResult = Some(HandResult(revealed, shown, awards)))
    ended.beginNextHandOrEnd(handDeck)
  }

  /** Splits the committed chips into a main pot and side pots and awards each to the best eligible hand.
    *
    * Pots are built from the distinct contribution levels across every seat (folded seats' chips are dead money in the
    * pot but win nothing). Each level's pot is contested only by the non-folded seats that reached it; at a showdown it
    * goes to the best five-card hand among them, split on a tie with any odd chip going to the first winner left of the
    * button. The seat that committed the most is always non-folded (a fold never leaves you the top contributor), so
    * every pot has at least one eligible winner and no chips are stranded — including an uncalled final bet, which
    * returns to its lone eligible contributor.
    *
    * @return the pot awards and the resulting stacks
    */
  private def computeAwards(showdown: Boolean): (List[PotAward], Vector[Int]) =
    if (!showdown) {
      // everyone else folded: the lone remaining player takes the whole pot (their own uncalled chips included)
      val winner = (0 until n).find(inHand).get
      (List(PotAward(pot, List(winner), None)), stacks.updated(winner, stacks(winner) + pot))
    } else {
      val levels: Seq[Int] = committed.filter(_ > 0).distinct.sorted
      val pots: Seq[(Int, Seq[Int])] = (0 +: levels).zip(levels).map { case (prev, level) =>
        val atLevel = (0 until n).filter(committed(_) >= level)
        ((level - prev) * atLevel.size, atLevel.filter(inHand))
      }
      val bestHands: Map[Int, Hand] =
        (0 until n).filter(inHand).map(s => s -> Hand.bestHand((holeCards(s) ++ board).toSet)).toMap

      val deltas = Array.fill(n)(0)
      val awards = pots.map { case (amount, eligible) =>
        val best = eligible.map(bestHands).max(Hand.handOrdering)
        val winners = eligible.filter(s => bestHands(s).compare(best) == 0).toList
        val share = amount / winners.size
        winners.foreach(w => deltas(w) += share)
        // odd chips go one at a time to the winners nearest the button's left
        val ordered = orderFrom(button).filter(winners.toSet)
        (0 until amount % winners.size).foreach(i => deltas(ordered(i)) += 1)
        PotAward(amount, winners, Some(bestHands(winners.head).description))
      }.toList

      (awards, Vector.tabulate(n)(s => stacks(s) + deltas(s)))
    }

  /** Ends the sit-and-go if one seat now holds every chip, otherwise deals the next hand from `handDeck`. */
  private def beginNextHandOrEnd(handDeck: List[Card]): TexasHoldEm = {
    val survivors = (0 until n).filter(stacks(_) > 0)
    if (survivors.size == 1) copy(winner = Some(survivors.head))
    else startHand(handDeck)
  }

  /** Deals a new hand: rotates the button, deals hole and community cards from `deck`, posts the blinds, and gives the
    * turn to the first seat to act — or, in the rare case where the blinds put everyone all-in, runs it to showdown.
    */
  private def startHand(deck: List[Card]): TexasHoldEm = {
    val newButton = nextSeat(button)(stacks(_) > 0).get
    val playing = (0 until n).filter(stacks(_) > 0)
    val dealt = playing.zipWithIndex.map { case (seat, i) =>
      seat -> deck.slice(i * HoleCards, i * HoleCards + HoleCards)
    }.toMap
    val boardStart = playing.size * HoleCards

    val fresh = copy(
      button = newButton,
      holeCards = Vector.tabulate(n)(s => dealt.getOrElse(s, Nil)),
      board = deck.slice(boardStart, boardStart + 5),
      deck = deck.drop(boardStart + 5),
      street = Street.PreFlop,
      streetContrib = Vector.fill(n)(0),
      committed = Vector.fill(n)(0),
      currentBet = 0,
      lastRaiseSize = BigBlind,
      hasActed = Vector.fill(n)(false),
      folded = Vector.tabulate(n)(stacks(_) == 0),
      allIn = Vector.fill(n)(false)
    )

    val blinded = fresh.postBlinds()
    if (blinded.contestableCount == 0) blinded.concludeHand(showdown = true, deck) else blinded
  }

  /** Posts the small and big blinds for the freshly dealt hand and sets the first seat to act preflop. */
  private def postBlinds(): TexasHoldEm = {
    val active = (0 until n).filter(stacks(_) > 0)
    val smallSeat = if (active.size == 2) button else nextSeat(button)(stacks(_) > 0).get
    val bigSeat = nextSeat(smallSeat)(stacks(_) > 0).get

    val posted = postBlind(smallSeat, SmallBlind).postBlind(bigSeat, BigBlind)
    val firstToAct = posted.orderFrom(bigSeat).find(posted.needsAction).getOrElse(bigSeat)
    posted.copy(currentBet = posted.streetContrib.max, lastRaiseSize = BigBlind, toAct = firstToAct)
  }

  private def postBlind(seat: Int, amount: Int): TexasHoldEm = putIn(seat, math.min(amount, stacks(seat)))

  /** Applies the effect of `playerId` leaving: they fold the current hand and forfeit their remaining stack (their
    * already-committed chips stay in the pot). The table then settles like any action, but a leave carries no fresh
    * cards, so a hand it collapses to a lone winner deals the next hand from this hand's unseen remaining [[deck]].
    * Ends the sit-and-go if only one seat is left with chips. Rejects a non-participant or an already-finished game.
    */
  override def playerLeft(playerId: PlayerId): Either[GameError, TexasHoldEm] =
    playerFor(playerId) match {
      case None                        => Left(InvalidPlayer(playerId))
      case Some(_) if winner.isDefined => Left(GameOver)
      case Some(seat)                  =>
        val left = copy(
          folded = folded.updated(seat, true),
          allIn = allIn.updated(seat, false),
          stacks = stacks.updated(seat, 0),
          hasActed = hasActed.updated(seat, true)
        )
        Right(left.settle(deck))
    }
}
