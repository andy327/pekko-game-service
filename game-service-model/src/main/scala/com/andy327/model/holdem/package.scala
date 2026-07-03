package com.andy327.model

/** Card primitives for poker: ranks, suits, and the hand-category enumeration. */
package object holdem {

  /** A card's rank as an integer from 2 to 14, where Jack = 11, Queen = 12, King = 13, and Ace = 14. */
  type Rank = Int

  /** One of the four card suits. Suits never affect a poker hand's strength; they matter only for flush detection and
    * for identifying a specific card.
    */
  sealed trait Suit {

    /** The single-letter code used for parsing and serialization (`S`, `H`, `C`, `D`). */
    def letter: String

    override def toString: String = letter
  }

  case object Spades extends Suit { val letter: String = "S" }
  case object Hearts extends Suit { val letter: String = "H" }
  case object Clubs extends Suit { val letter: String = "C" }
  case object Diamonds extends Suit { val letter: String = "D" }

  object Suit {

    /** All four suits. */
    val all: List[Suit] = List(Spades, Hearts, Clubs, Diamonds)

    /** Parses a single-letter suit code (`S`, `H`, `C`, `D`), or fails if it is not one of the four. */
    def apply(letter: String): Suit =
      all.find(_.letter == letter).getOrElse(sys.error(s"Invalid Suit representation: $letter"))
  }

  /** The nine poker hand categories, declared from weakest ([[HandType.HighCard]]) to strongest
    * ([[HandType.RoyalFlush]]). The enumeration's own ascending value order is what makes hands of different categories
    * directly comparable in [[Hand.handOrdering]].
    */
  object HandType extends Enumeration {
    val HighCard, OnePair, TwoPair, ThreeOfAKind, Straight, Flush, FullHouse, FourOfAKind, StraightFlush, RoyalFlush =
      Value
  }
}
