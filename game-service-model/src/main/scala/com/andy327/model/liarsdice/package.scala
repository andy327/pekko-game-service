package com.andy327.model

package object liarsdice {

  /** A standing or proposed bid over all dice on the table.
    *
    * A numbered bid (`face = Some(2..6)`) claims at least `quantity` dice show that face, counting wild 1s; a wild
    * "ones" bid (`face = None`) claims at least `quantity` ones. Ordering follows the printed bidding track — see
    * [[Bid.canRaiseTo]].
    *
    * @param quantity the number of dice claimed (for a "ones" bid, the number of ones)
    * @param face the numbered face 2–6, or `None` for a wild "ones" bid
    */
  final case class Bid(quantity: Int, face: Option[Int]) {

    /** True for a wild "ones" bid (no numbered face). */
    def isOnes: Boolean = face.isEmpty

    /** The bid's clockwise position on the track. A numbered space `q` sits at `2q`; a "k ones" space sits at `4k - 1`,
      * between odd quantity `2k - 1` and even quantity `2k`, so the two kinds of space interleave in track order.
      */
    def spaceRank: Int = if (isOnes) 4 * quantity - 1 else 2 * quantity

    /** A well-formed bid names a positive quantity and, if numbered, a face of 2–6. */
    def isWellFormed: Boolean = quantity >= 1 && face.forall(f => f >= 2 && f <= 6)

    /** Whether raising from this bid to `next` is a legal move on the track:
      *   - numbered → numbered: the same quantity needs a strictly higher face; a higher quantity may keep or raise the
      *     face but never lower it
      *   - numbered → ones, or ones → ones: any clockwise-later space (a "ones" space is always reachable, face-free)
      *   - ones → numbered: any face, provided the quantity reaches the number printed after the ones space
      *     (`quantity >= 2k`, which `spaceRank` encodes)
      */
    def canRaiseTo(next: Bid): Boolean = (face, next.face) match {
      case (Some(f), Some(nf)) =>
        if (next.quantity == quantity) nf > f
        else if (next.quantity > quantity) nf >= f
        else false
      case (Some(_), None) => next.spaceRank > spaceRank
      case (None, Some(_)) => next.spaceRank > spaceRank
      case (None, None)    => next.quantity > quantity
    }
  }

  /** The current standing bid together with the seat that made it — the seat a losing challenge would penalize. */
  final case class StandingBid(bid: Bid, bidderSeat: Int)

  /** A snapshot of the most recently resolved challenge — the one moment every die is public.
    *
    * Retained in the game state so all viewers (and reconnecting clients) can see how the last round ended, even after
    * the dice have been re-rolled for the next round.
    *
    * @param bid the bid that was challenged
    * @param count the true number of dice counting toward `bid` (its face plus wild ones)
    * @param allDice every seat's dice as revealed at the challenge, in seat order
    * @param challengerSeat the seat that called "Liar"
    * @param bidderSeat the seat whose bid was challenged
    * @param loserSeat the seat that lost dice this round
    * @param diceLost how many dice `loserSeat` lost (0 when a challenger meets the bid exactly)
    */
  final case class Reveal(
      bid: Bid,
      count: Int,
      allDice: Vector[Vector[Int]],
      challengerSeat: Int,
      bidderSeat: Int,
      loserSeat: Int,
      diceLost: Int
  )

  /** A player action on their turn: raise the bid or call "Liar." */
  sealed trait LiarsDiceMove

  /** Raise (or open) the bidding with `bid`. */
  final case class MakeBid(bid: Bid) extends LiarsDiceMove

  /** Challenge the standing bid. `freshDice` is a flat pool of server-rolled dice the model deals out to the surviving
    * hands for the next round, keeping the roll server-side so the model stays pure.
    */
  final case class Challenge(freshDice: List[Int]) extends LiarsDiceMove
}
