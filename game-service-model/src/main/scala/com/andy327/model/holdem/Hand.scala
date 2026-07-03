package com.andy327.model.holdem

object Hand {
  import scala.math.Ordering.Implicits._

  /** Orders hands by category first, then by the ranks of the cards making up the hand with kickers appended, so a
    * stronger category always beats a weaker one and ties within a category break on high card. Two hands compare equal
    * exactly when they tie at a showdown (a split pot), since suits never affect a poker hand's strength.
    */
  val handOrdering: Ordering[Hand] = Ordering.by((hand: Hand) => (hand.handType, hand.cardRanks))

  /** Builds a hand from up to five distinct cards. */
  def apply(cards: Card*): Hand = {
    val cardSet: Set[Card] = cards.toSet
    require(cards.size == cardSet.size, s"No duplicate cards allowed: ${cards.mkString(", ")}")
    Hand(cardSet)
  }

  /** The best five-card hand formable from the given cards — typically the seven a player holds at a Hold 'Em showdown
    * (two hole cards plus five community cards). Evaluates all 5-card combinations and keeps the strongest.
    */
  def bestHand(cards: Set[Card]): Hand =
    cards.toSeq.combinations(5).map(five => Hand(five.toSet)).max(handOrdering)
}

/** A poker hand of up to five cards, categorized and ranked so hands can be compared directly.
  *
  * Beyond the raw cards it derives the hand [[handType]], which cards form the hand versus which are kickers, and the
  * rank sequence [[cardRanks]] used to break ties. It implements `scala.math.Ordered` via [[Hand.handOrdering]], so
  * `>`, `<`, and `compare` all reflect poker hand strength and `compare == 0` marks a tie (a split pot).
  *
  * @param cards the up-to-five cards making up the hand
  */
final case class Hand(cards: Set[Card]) extends Ordered[Hand] {
  import HandType._

  require(cards.size <= 5, "A hand has at most five cards")

  val ranks: Set[Rank] = cards.map(_.rank)
  val suits: Set[Suit] = cards.map(_.suit)

  val cardsByRank: Map[Rank, Set[Card]] = cards.groupBy(_.rank)
  val cardsByFrequency: Map[Int, Set[Card]] = cards.groupBy(card => cardsByRank(card.rank).size)

  val quads: Set[Card] = cardsByFrequency.getOrElse(4, Set.empty)
  val triples: Set[Card] = cardsByFrequency.getOrElse(3, Set.empty)
  val pairs: Set[Card] = cardsByFrequency.getOrElse(2, Set.empty)
  val singles: Set[Card] = cardsByFrequency.getOrElse(1, Set.empty)

  val isFlush: Boolean = suits.size == 1 && cards.size == 5
  val isRoyal: Boolean = ranks == Set(14, 13, 12, 11, 10)
  val isWheel: Boolean = ranks == Set(5, 4, 3, 2, 14) // ace-low straight, the lowest-ranked straight
  val isStraight: Boolean = isWheel || (ranks.size == 5 && ranks.max - ranks.min == 4)

  val handType: HandType.Value =
    if (isRoyal && isFlush) RoyalFlush
    else if (isStraight && isFlush) StraightFlush
    else if (quads.nonEmpty) FourOfAKind
    else if (triples.nonEmpty && pairs.nonEmpty) FullHouse
    else if (isFlush) Flush
    else if (isStraight) Straight
    else if (triples.nonEmpty) ThreeOfAKind
    else if (pairs.size == 4) TwoPair
    else if (pairs.nonEmpty) OnePair
    else HighCard

  private val (usedCards, unusedCards): (Set[Card], Set[Card]) = handType match {
    case RoyalFlush | StraightFlush | FullHouse | Flush | Straight => (cards, Set.empty)
    case FourOfAKind                                               => (quads, singles)
    case ThreeOfAKind                                              => (triples, singles)
    case TwoPair | OnePair                                         => (pairs, singles)
    case HighCard                                                  => (Set.empty, cards)
  }

  /** The cards forming the hand, ordered for tie-breaking. Normally this is descending rank, but two categories need
    * special handling: a full house ranks by its trips before its pair (so "queens full of aces" ranks below "kings
    * full of fives", never above it), and the wheel drops its ace to the bottom so it ranks as a five-high straight.
    */
  val rankedCards: Seq[Card] =
    if (handType == FullHouse)
      triples.toSeq.sortBy(_.rank).reverse ++ pairs.toSeq.sortBy(_.rank).reverse
    else {
      val sorted = usedCards.toSeq.sortBy(_.rank).reverse
      if (isWheel) sorted.tail :+ sorted.head // A,5,4,3,2 -> 5,4,3,2,A so the ace ranks low
      else sorted
    }

  /** The leftover cards not part of the hand, ordered by descending rank; they break ties between equal categories. */
  val kickers: Seq[Card] = unusedCards.toSeq.sortBy(_.rank).reverse

  /** The ranks of every card, hand cards first then kickers — the tie-break key used by [[Hand.handOrdering]]. */
  val cardRanks: Seq[Rank] = (rankedCards ++ kickers).map(_.rank)

  /** A human-readable summary of the hand, e.g. "full house, Ks over 5s" or "ace-high straight" (used at showdown). */
  val description: String = handType match {
    case RoyalFlush    => "royal flush"
    case StraightFlush => s"${Card.rankToString(rankedCards.head.rank)}-high straight flush"
    case FourOfAKind   => s"four of a kind, ${Card.rankToString(quads.head.rank)}s"
    case FullHouse     =>
      val trip = Card.rankToString(triples.head.rank)
      val pair = Card.rankToString(pairs.head.rank)
      s"full house, ${trip}s over ${pair}s"
    case Flush        => s"${Card.rankToString(rankedCards.head.rank)}-high flush"
    case Straight     => s"${Card.rankToString(rankedCards.head.rank)}-high straight"
    case ThreeOfAKind => s"three of a kind, ${Card.rankToString(triples.head.rank)}s"
    case TwoPair      =>
      val pairRanks = pairs.map(_.rank).toSeq.sorted
      s"two pair, ${Card.rankToString(pairRanks(1))}s and ${Card.rankToString(pairRanks(0))}s"
    case OnePair  => s"one pair, ${Card.rankToString(pairs.head.rank)}s"
    case HighCard => s"${Card.rankToString(kickers.head.rank)} high"
  }

  def compare(that: Hand): Int = Hand.handOrdering.compare(this, that)

  override def toString: String = (rankedCards ++ kickers).mkString("[", " ", "]")
}
