package com.andy327.model.pig

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{Game, GameError, GameStatus, InProgress, PlayerId, Won}

object Pig {

  /** Points needed to win. */
  val WinScore: Int = 100

  /** Creates a fresh game with all scores at zero. */
  def newGame(playerIds: Seq[PlayerId]): Pig =
    Pig(
      playerIds = playerIds.toVector,
      scores = Vector.fill(playerIds.size)(0),
      currentSeat = 0,
      turnScore = 0,
      lastRoll = None,
      winner = None,
      moveCount = 0
    )

  /** Human-readable seat label for a zero-based seat index. */
  def seatLabel(seat: Int): String = s"P${seat + 1}"
}

/** An instance of a Pig dice game.
  *
  * On each turn the current player may Roll (accumulate points, but rolling a 1 busts the turn) or Hold (bank their
  * turn total). The first player to reach [[Pig.WinScore]] banked points wins. The die is rolled by the server before
  * applying a [[Roll]] move, so the model itself is pure and every [[Roll]] already carries its result.
  *
  * @param playerIds ordered list of participating players; the seat index into this vector is the game's player token
  * @param scores banked points per seat, indexed in the same order as `playerIds`
  * @param currentSeat zero-based index of the player whose turn it is
  * @param turnScore points accumulated this turn, not yet banked; lost on a roll of 1
  * @param lastRoll the die value from the most recent roll, or None before any roll has been made
  * @param winner the winning seat index once the game is over, otherwise None
  * @param moveCount total rolls and holds applied so far; survives serialization for the history log
  */
final case class Pig(
    playerIds: Vector[PlayerId],
    scores: Vector[Int],
    currentSeat: Int,
    turnScore: Int,
    lastRoll: Option[Int],
    winner: Option[Int],
    moveCount: Int
) extends Game[PigMove, Pig, Int, GameStatus[Int], GameError] {

  import Pig._

  def currentState: Pig = this
  def currentPlayer: Int = currentSeat
  def gameStatus: GameStatus[Int] = winner.map(Won(_)).getOrElse(InProgress)
  def players: List[PlayerId] = playerIds.toList

  /** Resolves a platform player ID to the zero-based seat index, or `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Int] = {
    val i = playerIds.indexOf(playerId)
    if (i >= 0) Some(i) else None
  }

  private def nextSeat: Int = (currentSeat + 1) % playerIds.size

  /** Applies a player action.
    *
    * Validates turn order. On `Roll`:
    *   - result 1: bust — turn score is lost, turn passes to the next player
    *   - result 2–6: added to `turnScore`; the turn continues (a win can only be triggered by Hold, not Roll)
    *
    * On `Hold`: banks `turnScore` into `scores`; if that reaches [[Pig.WinScore]] the current player wins, otherwise
    * the turn passes. Holding with zero turn score (no roll yet this turn) is rejected.
    */
  def play(player: Int, move: PigMove): Either[GameError, Pig] =
    if (gameStatus != InProgress)
      Left(GameOver)
    else if (player != currentSeat)
      Left(InvalidTurn)
    else
      move match {

        case Roll(result) =>
          if (result == 1) {
            // bust: lose turn score, advance to next player
            Right(copy(currentSeat = nextSeat, turnScore = 0, lastRoll = Some(1), moveCount = moveCount + 1))
          } else {
            Right(copy(turnScore = turnScore + result, lastRoll = Some(result), moveCount = moveCount + 1))
          }

        case Hold =>
          if (turnScore == 0)
            Left(NothingToHold)
          else {
            val banked = scores(currentSeat) + turnScore
            val base = copy(
              scores = scores.updated(currentSeat, banked),
              turnScore = 0,
              lastRoll = None,
              moveCount = moveCount + 1
            )
            if (banked >= WinScore)
              Right(base.copy(winner = Some(currentSeat)))
            else
              Right(base.copy(currentSeat = nextSeat))
          }
      }

  /** The leaver forfeits; the non-leaver with the highest banked score wins (lowest seat index breaks a tie).
    *
    * If the leaver is the only player ahead by score, the runner-up wins — leaving never lets the leaver take the
    * trophy. Rejects with [[core.GameError.InvalidPlayer]] if `playerId` is not a participant, or
    * [[core.GameError.GameOver]] if the game has already ended.
    */
  override def playerLeft(playerId: PlayerId): Either[GameError, Pig] =
    playerFor(playerId) match {
      case None                                => Left(GameError.InvalidPlayer(playerId))
      case Some(_) if gameStatus != InProgress => Left(GameOver)
      case Some(leaverSeat)                    =>
        val winnerSeat = playerIds.indices
          .filter(_ != leaverSeat)
          .maxBy(i => (scores(i), -i))
        Right(copy(winner = Some(winnerSeat)))
    }
}
