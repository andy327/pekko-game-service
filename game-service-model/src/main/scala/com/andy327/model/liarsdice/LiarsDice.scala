package com.andy327.model.liarsdice

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{Game, GameError, GameStatus, InProgress, PlayerId, Won}

object LiarsDice {

  /** Dice each player starts with. */
  val DicePerPlayer: Int = 5

  /** Maximum number of players (mirrors `GameType.LiarsDice.maxPlayers`). */
  val MaxPlayers: Int = 6

  /** Upper bound on the number of dice on the table, and hence the size of a fresh-dice pool a challenge must carry. */
  val MaxTotalDice: Int = MaxPlayers * DicePerPlayer

  /** Human-readable seat label for a zero-based seat index. */
  def seatLabel(seat: Int): String = s"P${seat + 1}"

  /** Deals a flat pool of freshly rolled dice into per-seat hands of the given sizes, in seat order.
    *
    * Each seat takes a prefix of what remains of `pool`; a size of 0 (an eliminated seat) yields an empty hand. The
    * pool must hold at least `sizes.sum` dice. This is the single path by which server-rolled dice enter the pure
    * model: the actor/module rolls the pool, the model deals it — so `play` never calls a random source.
    */
  def deal(pool: List[Int], sizes: Seq[Int]): Vector[Vector[Int]] =
    sizes
      .foldLeft((Vector.empty[Vector[Int]], pool)) { case ((hands, remaining), n) =>
        (hands :+ remaining.take(n).toVector, remaining.drop(n))
      }
      ._1

  /** The number of dice counting toward `bid` across `allDice`: its face plus every wild 1, or — for a wild "ones"
    * bid — simply the number of 1s.
    */
  def countToward(bid: Bid, allDice: Vector[Vector[Int]]): Int = {
    val flat = allDice.flatten
    bid.face match {
      case None    => flat.count(_ == 1)
      case Some(f) => flat.count(d => d == f || d == 1)
    }
  }

  /** Creates a fresh game: every player holds [[DicePerPlayer]] dice dealt from `pool`, seat 0 opens the bidding.
    *
    * `pool` is rolled server-side (in the actor layer) and must contain at least `players.size * DicePerPlayer` dice.
    */
  def newGame(playerIds: Seq[PlayerId], pool: List[Int]): LiarsDice =
    LiarsDice(
      playerIds = playerIds.toVector,
      dice = deal(pool, Vector.fill(playerIds.size)(DicePerPlayer)),
      currentSeat = 0,
      standing = None,
      lastReveal = None,
      winner = None,
      moveCount = 0
    )
}

/** An instance of a Liar's Dice game (the "Wild Ones" house rules).
  *
  * Each round every remaining player has hidden dice; on their turn a player either raises the bid ([[MakeBid]]) or
  * calls "Liar" ([[Challenge]]). A bid names a quantity and a face over all dice on the table, with 1s wild, and must
  * advance along the bidding track (see [[Bid.canRaiseTo]]). A challenge reveals every die: if the true count meets or
  * beats the bid the challenger loses a die for each die the count overshoots by (none on an exact meet), otherwise the
  * bidder loses a single die. The round's loser starts the next round; a player is eliminated at zero dice and the last
  * player holding dice wins.
  *
  * The model is pure: dice are rolled server-side and supplied to it — the initial hands via [[LiarsDice.newGame]], and
  * each round's re-roll via the [[Challenge]] move's dice pool, which the model deals to the survivors.
  *
  * @param playerIds ordered list of participating players; the seat index into this vector is the game's player token
  * @param dice each seat's hidden dice, indexed in the same order as `playerIds`; an empty hand means eliminated
  * @param currentSeat zero-based index of the player whose turn it is
  * @param standing the current standing bid and who made it, or None at the start of a round
  * @param lastReveal the most recently resolved challenge, or None before any challenge
  * @param winner the winning seat index once the game is over, otherwise None
  * @param moveCount total bids and challenges applied so far; survives serialization for the history log
  */
final case class LiarsDice(
    playerIds: Vector[PlayerId],
    dice: Vector[Vector[Int]],
    currentSeat: Int,
    standing: Option[StandingBid],
    lastReveal: Option[Reveal],
    winner: Option[Int],
    moveCount: Int
) extends Game[LiarsDiceMove, LiarsDice, Int, GameStatus[Int], GameError] {

  import LiarsDice._

  def currentState: LiarsDice = this
  def currentPlayer: Int = currentSeat
  def gameStatus: GameStatus[Int] = winner.map(Won(_)).getOrElse(InProgress)
  def players: List[PlayerId] = playerIds.toList

  /** Resolves a platform player ID to the zero-based seat index, or `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Int] = {
    val i = playerIds.indexOf(playerId)
    if (i >= 0) Some(i) else None
  }

  /** Number of dice seat `seat` still holds; a seat with none has been eliminated. */
  def diceCount(seat: Int): Int = dice(seat).size

  /** Next seat clockwise from `seat` that still holds dice. Only called while at least one other seat is active (during
    * a round, or when advancing past an eliminated loser), so the search always terminates.
    */
  private def nextActiveSeat(seat: Int): Int = {
    val n = playerIds.size
    Iterator.iterate((seat + 1) % n)(s => (s + 1) % n).find(diceCount(_) > 0).get
  }

  /** Applies a player action.
    *
    * Validates turn order, then either raises the bid or resolves a challenge. See [[MakeBid]]/[[Challenge]] and the
    * class comment for the rules each enforces.
    */
  def play(player: Int, move: LiarsDiceMove): Either[GameError, LiarsDice] =
    if (gameStatus != InProgress) Left(GameOver)
    else if (player != currentSeat) Left(InvalidTurn)
    else
      move match {
        case MakeBid(bid)         => makeBid(bid)
        case Challenge(freshDice) => challenge(freshDice)
      }

  /** Opens or raises the bidding. The opening bid of a round may be any well-formed bid; a later bid must legally raise
    * the standing bid. On success the bid becomes the new standing bid and the turn passes to the next active seat.
    */
  private def makeBid(bid: Bid): Either[GameError, LiarsDice] =
    if (!bid.isWellFormed) Left(InvalidBid)
    else
      standing match {
        case Some(StandingBid(current, _)) if !current.canRaiseTo(bid) => Left(IllegalRaise)
        case _                                                         =>
          Right(
            copy(
              standing = Some(StandingBid(bid, currentSeat)),
              currentSeat = nextActiveSeat(currentSeat),
              moveCount = moveCount + 1
            )
          )
      }

  /** Resolves a challenge against the standing bid, then deals the next round's dice from `freshDice`.
    *
    * The true count is compared to the bid: `count >= quantity` means the challenger was wrong and loses the overshoot
    * (zero dice on an exact meet), otherwise the bidder loses a single die. The loser starts the next round, or — if
    * they were eliminated — the next active seat does. When only one seat is left holding dice, that seat wins.
    */
  private def challenge(freshDice: List[Int]): Either[GameError, LiarsDice] =
    standing match {
      case None                               => Left(NoBidToChallenge)
      case Some(StandingBid(bid, bidderSeat)) =>
        val challengerSeat = currentSeat
        val count = countToward(bid, dice)
        val (loserSeat, rawLoss) =
          if (count >= bid.quantity) (challengerSeat, count - bid.quantity)
          else (bidderSeat, 1)
        val diceLost = math.min(rawLoss, diceCount(loserSeat))

        val newSizes =
          playerIds.indices.toVector.map(s => if (s == loserSeat) diceCount(s) - diceLost else diceCount(s))
        val reveal = Reveal(bid, count, dice, challengerSeat, bidderSeat, loserSeat, diceLost)
        val base =
          copy(dice = deal(freshDice, newSizes), standing = None, lastReveal = Some(reveal), moveCount = moveCount + 1)

        val survivors = newSizes.indices.filter(newSizes(_) > 0)
        if (survivors.size == 1)
          Right(base.copy(winner = Some(survivors.head)))
        else {
          val starter = if (newSizes(loserSeat) > 0) loserSeat else base.nextActiveSeat(loserSeat)
          Right(base.copy(currentSeat = starter))
        }
    }

  /** The leaver forfeits: their dice are set aside. If only one player remains they win; otherwise the round resets —
    * the standing bid is cleared and the turn moves to the next active seat if the leaver was on the clock.
    *
    * No dice are re-rolled: a leave carries no fresh dice, and the surviving hands are unaffected. Rejects with
    * [[core.GameError.InvalidPlayer]] for a non-participant or [[core.GameError.GameOver]] once the game has ended.
    */
  override def playerLeft(playerId: PlayerId): Either[GameError, LiarsDice] =
    playerFor(playerId) match {
      case None                                => Left(GameError.InvalidPlayer(playerId))
      case Some(_) if gameStatus != InProgress => Left(GameOver)
      case Some(leaverSeat)                    =>
        val newDice = dice.updated(leaverSeat, Vector.empty[Int])
        val base = copy(dice = newDice, standing = None)
        val survivors = newDice.indices.filter(newDice(_).nonEmpty)
        if (survivors.size == 1)
          Right(base.copy(winner = Some(survivors.head)))
        else
          Right(
            base.copy(currentSeat = if (currentSeat == leaverSeat) base.nextActiveSeat(leaverSeat) else currentSeat)
          )
    }
}
