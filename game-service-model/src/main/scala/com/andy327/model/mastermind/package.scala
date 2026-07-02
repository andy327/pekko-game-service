package com.andy327.model

package object mastermind {

  /** A single code peg — one of the six colors a code or guess is built from. Serialized by its lowercase `name`. */
  sealed trait Peg {
    def name: String
    override def toString: String = name
  }

  object Peg {
    case object Red extends Peg { val name = "red" }
    case object Yellow extends Peg { val name = "yellow" }
    case object Blue extends Peg { val name = "blue" }
    case object Green extends Peg { val name = "green" }
    case object Black extends Peg { val name = "black" }
    case object White extends Peg { val name = "white" }

    /** The full palette (the classic Mastermind six), in a fixed display order. Its size is the number of colors to
      * choose from.
      */
    val all: List[Peg] = List(Red, Yellow, Blue, Green, Black, White)

    private val byName: Map[String, Peg] = all.map(peg => peg.name -> peg).toMap

    /** Resolves a (case-insensitive) color name to its [[Peg]], or `None` if it is not a valid color. */
    def fromName(s: String): Option[Peg] = byName.get(s.toLowerCase)
  }

  /** Which of the two asymmetric roles a player holds. The codemaker (seat 0) sets the secret code and then waits; the
    * codebreaker (seat 1) takes every subsequent turn. The seat order rotates across rematches, so the role alternates.
    */
  sealed trait Role {
    def label: String
    override def toString: String = label
  }
  case object Codemaker extends Role { val label = "codemaker" }
  case object Codebreaker extends Role { val label = "codebreaker" }

  /** Scoring for one guess: `black` pegs are the right color in the right position; `white` pegs are the right color in
    * the wrong position. Neither counts a color more times than it appears in the secret.
    */
  case class Feedback(black: Int, white: Int)

  /** One codebreaker guess together with the feedback it earned. */
  case class Attempt(guess: Vector[Peg], feedback: Feedback)

  /** A player action. The codemaker plays [[SetCode]] once; the codebreaker plays [[Guess]] on every turn. */
  sealed trait MastermindMove

  /** The codemaker fixes the secret code (played once, before any guessing). */
  case class SetCode(pegs: Vector[Peg]) extends MastermindMove

  /** The codebreaker guesses the secret code. */
  case class Guess(pegs: Vector[Peg]) extends MastermindMove
}
