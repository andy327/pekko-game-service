package com.andy327.model.holdem

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class HandSpec extends AnyWordSpec with Matchers {
  import HandType._

  /** Builds a hand from cards written in text form, e.g. `hand("AS", "KS", "QS", "JS", "TS")`. */
  private def hand(cards: String*): Hand = Hand(cards.map(Card(_)).toSet)

  /** True when `a` outranks `b` (a wins the pot outright). */
  private def beats(a: Hand, b: Hand): Boolean = a.compare(b) > 0

  /** True when `a` and `b` tie (a split pot). */
  private def ties(a: Hand, b: Hand): Boolean = a.compare(b) == 0

  "Card" should {
    "parse rank-and-suit text into a Card" in {
      Card("AS") shouldBe Card(14, Spades)
      Card("TD") shouldBe Card(10, Diamonds)
      Card("9H") shouldBe Card(9, Hearts)
      Card("2C") shouldBe Card(2, Clubs)
    }

    "reject a rank outside 2–14" in {
      an[IllegalArgumentException] should be thrownBy Card(1, Spades)
      an[IllegalArgumentException] should be thrownBy Card(15, Spades)
    }

    "expose a full 52-card deck of distinct cards" in {
      Card.deck.size shouldBe 52
      Card.deck.toSet.size shouldBe 52
    }

    "render each rank as its display symbol" in {
      Card.rankToString(14) shouldBe "A"
      Card.rankToString(13) shouldBe "K"
      Card.rankToString(12) shouldBe "Q"
      Card.rankToString(11) shouldBe "J"
      Card.rankToString(10) shouldBe "T"
      Card.rankToString(9) shouldBe "9"
    }

    "render as its rank symbol followed by its suit letter" in {
      Card(11, Clubs).toString shouldBe "JC"
      Card(10, Diamonds).toString shouldBe "TD"
    }
  }

  "Suit" should {
    "render as its single-letter code" in {
      Spades.toString shouldBe "S"
      Hearts.toString shouldBe "H"
      Clubs.toString shouldBe "C"
      Diamonds.toString shouldBe "D"
    }

    "parse a single-letter code back into a suit" in {
      Suit("S") shouldBe Spades
      Suit("D") shouldBe Diamonds
    }

    "reject an unknown suit code" in {
      a[RuntimeException] should be thrownBy Suit("Z")
    }
  }

  "Hand construction" should {
    "accept up to five distinct cards passed individually" in {
      val h = Hand(Card("AS"), Card("KS"), Card("QS"), Card("JS"), Card("TS"))
      h.cards should have size 5
      h.handType shouldBe RoyalFlush
    }

    "reject duplicate cards" in {
      an[IllegalArgumentException] should be thrownBy Hand(Card("AS"), Card("AS"))
    }

    "reject more than five cards" in {
      val six = Set("AS", "KS", "QS", "JS", "TS", "9S").map(Card(_))
      an[IllegalArgumentException] should be thrownBy Hand(six)
    }

    "render its cards from strongest rank to weakest" in {
      hand("AS", "KH", "QD", "JC", "9S").toString shouldBe "[AS KH QD JC 9S]"
    }
  }

  "Hand categorization" should {
    "detect a royal flush" in {
      hand("AS", "KS", "QS", "JS", "TS").handType shouldBe RoyalFlush
    }
    "detect a straight flush" in {
      hand("9H", "8H", "7H", "6H", "5H").handType shouldBe StraightFlush
    }
    "treat a wheel straight flush as a straight flush" in {
      hand("5D", "4D", "3D", "2D", "AD").handType shouldBe StraightFlush
    }
    "detect four of a kind" in {
      hand("7S", "7H", "7C", "7D", "KS").handType shouldBe FourOfAKind
    }
    "detect a full house" in {
      hand("KS", "KH", "KD", "5C", "5S").handType shouldBe FullHouse
    }
    "detect a flush" in {
      hand("AH", "JH", "8H", "5H", "2H").handType shouldBe Flush
    }
    "detect a straight" in {
      hand("9S", "8H", "7C", "6D", "5S").handType shouldBe Straight
    }
    "treat the wheel as the lowest straight" in {
      hand("5C", "4D", "3H", "2S", "AS").handType shouldBe Straight
    }
    "detect three of a kind" in {
      hand("QS", "QH", "QD", "9C", "2S").handType shouldBe ThreeOfAKind
    }
    "detect two pair" in {
      hand("KS", "KH", "5D", "5C", "AS").handType shouldBe TwoPair
    }
    "detect one pair" in {
      hand("KS", "KH", "9D", "5C", "2S").handType shouldBe OnePair
    }
    "detect high card" in {
      hand("KS", "JH", "9D", "5C", "2S").handType shouldBe HighCard
    }
  }

  "Hand ranking" should {
    "rank the categories in order" in {
      val ordered = List(
        hand("KS", "JH", "9D", "5C", "2S"), // high card
        hand("KS", "KH", "9D", "5C", "2S"), // one pair
        hand("KS", "KH", "5D", "5C", "AS"), // two pair
        hand("QS", "QH", "QD", "9C", "2S"), // three of a kind
        hand("9S", "8H", "7C", "6D", "5S"), // straight
        hand("AH", "JH", "8H", "5H", "2H"), // flush
        hand("KS", "KH", "KD", "5C", "5S"), // full house
        hand("7S", "7H", "7C", "7D", "KS"), // four of a kind
        hand("9H", "8H", "7H", "6H", "5H"), // straight flush
        hand("AS", "KS", "QS", "JS", "TS") // royal flush
      )
      ordered.zip(ordered.tail).foreach { case (weaker, stronger) => beats(stronger, weaker) shouldBe true }
    }

    "break ties on kickers for the same category" in {
      beats(hand("KS", "KH", "AD", "5C", "2S"), hand("KS", "KH", "QD", "5C", "2S")) shouldBe true
    }

    "break two-pair ties on the fifth card" in {
      beats(hand("KS", "KH", "5D", "5C", "AS"), hand("KH", "KD", "5H", "5S", "2C")) shouldBe true
    }

    "rank the wheel below a six-high straight" in {
      beats(hand("6S", "5H", "4C", "3D", "2S"), hand("5C", "4D", "3H", "2S", "AS")) shouldBe true
    }

    "rank a broadway straight above every other straight" in {
      beats(hand("AS", "KH", "QC", "JD", "TS"), hand("KS", "QH", "JC", "TD", "9S")) shouldBe true
    }

    "rank full houses by the trips first even when the pair outranks them" in {
      // kings full of fives beats queens full of aces, though the aces are the highest card present
      beats(hand("KS", "KH", "KD", "5C", "5S"), hand("QS", "QH", "QD", "AC", "AS")) shouldBe true
    }

    "rank full houses by the pair only when the trips tie" in {
      beats(hand("AS", "AH", "AD", "KC", "KS"), hand("AC", "AD", "AH", "QC", "QS")) shouldBe true
    }

    "treat two flushes with identical ranks as a tie" in {
      ties(hand("AS", "KS", "QS", "JS", "9S"), hand("AH", "KH", "QH", "JH", "9H")) shouldBe true
    }
  }

  "Hand.bestHand" should {
    "find the best five of seven cards" in {
      Hand.bestHand(Set("AS", "KS", "QS", "JS", "TS", "2D", "3C").map(Card(_))).handType shouldBe RoyalFlush
    }

    "prefer a full house over a lower flush draw of seven cards" in {
      val best = Hand.bestHand(Set("AS", "AH", "AD", "KS", "KH", "2C", "3D").map(Card(_)))
      best.handType shouldBe FullHouse
      best.description shouldBe "full house, As over Ks"
    }

    "pick the highest kicker when the made hand leaves a choice" in {
      // trip fives with an ace and a king available as kickers -> keep the ace
      val best = Hand.bestHand(Set("5S", "5H", "5D", "AC", "KD", "2S", "3H").map(Card(_)))
      best.handType shouldBe ThreeOfAKind
      best.cardRanks shouldBe Seq(5, 5, 5, 14, 13)
    }
  }
}
