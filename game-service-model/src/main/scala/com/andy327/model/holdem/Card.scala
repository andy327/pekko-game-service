package com.andy327.model.holdem

object Card {

  /** Renders a rank as its display symbol: `A`, `K`, `Q`, `J`, `T`, or the number itself for 2–9. */
  def rankToString(rank: Rank): String = rank match {
    case 14 => "A"
    case 13 => "K"
    case 12 => "Q"
    case 11 => "J"
    case 10 => "T"
    case n  => n.toString
  }

  /** Parses a card from its text form — a rank symbol followed by a suit letter — e.g. `"AS"`, `"TD"`, `"9H"`. */
  def apply(text: String): Card = {
    val rank: Rank = text.dropRight(1) match {
      case "A" => 14
      case "K" => 13
      case "Q" => 12
      case "J" => 11
      case "T" => 10
      case n   => n.toInt
    }
    Card(rank, Suit(text.takeRight(1)))
  }

  /** A standard 52-card deck in a fixed order (rank-major, then suit). Shuffling happens server-side in the actor
    * layer, never here, so the model stays pure.
    */
  val deck: List[Card] =
    (for {
      rank <- 2 to 14
      suit <- Suit.all
    } yield Card(rank, suit)).toList
}

/** A single playing card.
  *
  * @param rank the rank, 2–14 (Jack = 11, Queen = 12, King = 13, Ace = 14)
  * @param suit the suit
  */
final case class Card(rank: Rank, suit: Suit) {
  require(rank >= 2 && rank <= 14, s"Rank must be between 2 and 14 (Ace), got $rank")

  override def toString: String = s"${Card.rankToString(rank)}$suit"
}
