package com.andy327.model.mastermind

import scala.util.Random

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{Game, GameError, GameStatus, InProgress, PlayerId, Won}

object Mastermind {

  /** Number of pegs in a code. */
  val CodeLength: Int = 4

  /** Guesses the codebreaker gets before the codemaker wins by default. */
  val MaxGuesses: Int = 10

  /** Creates a fresh game with no code set yet. `playerIds(0)` is the codemaker (who moves first, to set the code) and
    * `playerIds(1)` is the codebreaker.
    */
  def newGame(playerIds: Seq[PlayerId]): Mastermind =
    Mastermind(
      codemakerId = playerIds(0),
      codebreakerId = playerIds(1),
      secret = None,
      guesses = Nil,
      winner = None
    )

  /** A random code, each peg drawn uniformly from the palette with duplicates allowed. Not used in normal play (the
    * codemaker sets the code); handy for tests and future AI players.
    *
    * @param rng source of randomness; inject a seeded `Random` for a deterministic code
    */
  def randomCode(rng: Random = new Random): Vector[Peg] =
    Vector.fill(CodeLength)(Peg.all(rng.nextInt(Peg.all.size)))

  /** Computes black/white peg feedback for `guess` against `secret`.
    *
    * `black` counts positions holding the right color; `white` counts colors present but out of position. Duplicates
    * are handled by summing, per color, the smaller of its counts in the secret and the guess, then subtracting the
    * black pegs — so no color is ever counted more times than it actually appears.
    */
  def feedback(secret: Vector[Peg], guess: Vector[Peg]): Feedback = {
    val black = secret.zip(guess).count { case (s, g) => s == g }
    val totalMatches = Peg.all.map(color => math.min(secret.count(_ == color), guess.count(_ == color))).sum
    Feedback(black, totalMatches - black)
  }
}

/** An instance of a Mastermind game.
  *
  * The game is asymmetric. The codemaker sets a hidden secret code with a single [[SetCode]] move; from then on the
  * codebreaker takes every turn, playing [[Guess]] moves and receiving black/white peg [[Feedback]] after each. The
  * codebreaker wins by reproducing the code exactly within [[Mastermind.MaxGuesses]] guesses; if those run out first,
  * the codemaker wins. The full secret is always retained in the model — hiding it from the codebreaker is a
  * presentation concern handled in the server's view layer, not here.
  *
  * @param codemakerId the player who sets the code (seat 0)
  * @param codebreakerId the player who guesses (seat 1)
  * @param secret the hidden code, or None until the codemaker has set it
  * @param guesses the codebreaker's guesses and their feedback, oldest first
  * @param winner the winning role once the game is over, otherwise None
  */
final case class Mastermind(
    codemakerId: PlayerId,
    codebreakerId: PlayerId,
    secret: Option[Vector[Peg]],
    guesses: List[Attempt],
    winner: Option[Role]
) extends Game[MastermindMove, Mastermind, Role, GameStatus[Role], GameError] {

  import Mastermind._

  def currentState: Mastermind = this

  /** The codemaker acts first (to set the code); once the code is set, every remaining turn belongs to the codebreaker. */
  def currentPlayer: Role = if (secret.isEmpty) Codemaker else Codebreaker

  /** Mastermind cannot draw: the codebreaker either cracks the code or exhausts their guesses (a codemaker win). */
  def gameStatus: GameStatus[Role] = winner.map(Won(_)).getOrElse(InProgress)

  /** Resolves `playerId` to `Codemaker` or `Codebreaker` based on their seat; `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Role] =
    if (playerId == codemakerId) Some(Codemaker)
    else if (playerId == codebreakerId) Some(Codebreaker)
    else None

  /** The roster in seat order: the codemaker (seat 0) then the codebreaker (seat 1). */
  def players: List[PlayerId] = List(codemakerId, codebreakerId)

  /** One move for setting the code plus one per guess; derived from state so it survives a restart. */
  def moveCount: Int = (if (secret.isDefined) 1 else 0) + guesses.size

  /** Applies a player action.
    *
    * Validates turn order (the codemaker only moves while no code is set; the codebreaker only after). A [[SetCode]]
    * fixes the code once, transitioning play to the codebreaker. A [[Guess]] records its feedback; a full match wins for
    * the codebreaker, and the [[Mastermind.MaxGuesses]]th guess without a match wins for the codemaker.
    */
  def play(player: Role, move: MastermindMove): Either[GameError, Mastermind] =
    if (gameStatus != InProgress)
      Left(GameOver)
    else if (player != currentPlayer)
      Left(InvalidTurn)
    else
      move match {

        case SetCode(pegs) =>
          // reachable when the codebreaker (whose turn it is once the code is set) sends a SetCode
          if (secret.isDefined) Left(CodeAlreadySet)
          else if (pegs.size != CodeLength) Left(InvalidCodeLength)
          else Right(copy(secret = Some(pegs)))

        case Guess(pegs) =>
          secret match {
            // reachable when the codemaker (whose turn it is before the code is set) sends a Guess
            case None       => Left(CodeNotSet)
            case Some(code) =>
              if (pegs.size != CodeLength) Left(InvalidCodeLength)
              else {
                val fb = feedback(code, pegs)
                val updated = copy(guesses = guesses :+ Attempt(pegs, fb))
                if (fb.black == CodeLength)
                  Right(updated.copy(winner = Some(Codebreaker)))
                else if (updated.guesses.size >= MaxGuesses)
                  Right(updated.copy(winner = Some(Codemaker)))
                else
                  Right(updated)
              }
          }
      }

  /** Forfeits the game on behalf of the leaving player: the other role is declared the winner.
    *
    * Rejects with [[core.GameError.InvalidPlayer]] if `playerId` is not a participant, or [[core.GameError.GameOver]]
    * if the game has already ended.
    */
  override def playerLeft(playerId: PlayerId): Either[GameError, Mastermind] =
    playerFor(playerId) match {
      case None                                => Left(GameError.InvalidPlayer(playerId))
      case Some(_) if gameStatus != InProgress => Left(GameOver)
      case Some(Codemaker)                     => Right(copy(winner = Some(Codebreaker)))
      case Some(Codebreaker)                   => Right(copy(winner = Some(Codemaker)))
    }
}
