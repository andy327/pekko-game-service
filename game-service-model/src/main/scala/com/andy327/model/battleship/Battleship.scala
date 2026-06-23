package com.andy327.model.battleship

import scala.annotation.tailrec
import scala.util.Random

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{Game, GameError, GameStatus, InProgress, PlayerId, Renderable, Won}

object Battleship {

  /** Side length of the (square) board. */
  val Size: Int = 10

  /** Ship lengths making up each player's fleet (Carrier, Battleship, Cruiser, Submarine, Destroyer). */
  val ShipSizes: List[Int] = List(5, 4, 3, 3, 2)

  /** Creates a new game with each player's fleet placed at random — non-overlapping and within bounds. Player1 fires
    * first.
    *
    * @param rng source of randomness; inject a seeded `Random` for deterministic placement (e.g. in tests)
    */
  def random(player1Id: PlayerId, player2Id: PlayerId, rng: Random = new Random): Battleship =
    Battleship(
      player1Id = player1Id,
      player2Id = player2Id,
      board1 = PlayerBoard(placeFleet(rng), Set.empty),
      board2 = PlayerBoard(placeFleet(rng), Set.empty),
      currentPlayer = Player1,
      winner = None
    )

  /** Places one ship of length `len` at a random orientation and position that stays in bounds and avoids `occupied`. */
  private def placeShip(len: Int, occupied: Set[Coord], rng: Random): Ship = {
    @tailrec
    def attempt(): Ship = {
      val horizontal = rng.nextBoolean()
      val row = rng.nextInt(if (horizontal) Size else Size - len + 1)
      val col = rng.nextInt(if (horizontal) Size - len + 1 else Size)
      val cells = (0 until len).map(i => if (horizontal) Coord(row, col + i) else Coord(row + i, col)).toSet
      if (cells.intersect(occupied).isEmpty) Ship(cells) else attempt()
    }
    attempt()
  }

  /** Places the full [[ShipSizes]] fleet one ship at a time, avoiding overlaps. */
  private def placeFleet(rng: Random): List[Ship] =
    ShipSizes
      .foldLeft((List.empty[Ship], Set.empty[Coord])) { case ((ships, occupied), len) =>
        val ship = placeShip(len, occupied, rng)
        (ships :+ ship, occupied ++ ship.cells)
      }
      ._1
}

/** Represents an instance of a Battleship game, including both the current state and the rules for how it evolves.
  *
  * Each player has their own board holding their fleet and the shots the opponent has fired at it. Players alternate
  * firing one shot per turn at the opponent's board; a shot is a hit if it lands on a ship cell. A player wins once
  * every cell of the opponent's fleet has been hit. The full state (both fleets) is always retained — hiding the
  * opponent's ships from a player is a presentation concern handled in the server's view layer, not in the model.
  *
  * @param player1Id the player ID seated as Player1 (fires first)
  * @param player2Id the player ID seated as Player2
  * @param board1 Player1's own waters: their fleet and the shots Player2 has fired
  * @param board2 Player2's own waters: their fleet and the shots Player1 has fired
  * @param currentPlayer the seat whose turn it is to fire
  * @param winner the winning seat if the game is over, otherwise None
  */
final case class Battleship(
    player1Id: PlayerId,
    player2Id: PlayerId,
    board1: PlayerBoard,
    board2: PlayerBoard,
    currentPlayer: Seat,
    winner: Option[Seat]
) extends Game[Fire, Battleship, Seat, GameStatus[Seat], GameError]
    with Renderable {

  import Battleship._

  def currentState: Battleship = this

  /** Battleship cannot end in a draw — the game ends as soon as one fleet is fully sunk. */
  def gameStatus: GameStatus[Seat] = winner.map(Won(_)).getOrElse(InProgress)

  /** Resolves `playerId` to `Player1` or `Player2` based on which seat they occupy; `None` if not a participant. */
  def playerFor(playerId: PlayerId): Option[Seat] =
    if (playerId == player1Id) Some(Player1)
    else if (playerId == player2Id) Some(Player2)
    else None

  /** The roster in seat order: `Player1` then `Player2`. */
  def players: List[PlayerId] = List(player1Id, player2Id)

  /** The number of shots fired so far across both boards — one per move. */
  def moveCount: Int = board1.shots.size + board2.shots.size

  /** The board belonging to `seat`. */
  private def boardFor(seat: Seat): PlayerBoard = seat match {
    case Player1 => board1
    case Player2 => board2
  }

  private def opponent(seat: Seat): Seat = seat match {
    case Player1 => Player2
    case Player2 => Player1
  }

  /** A copy of this game with `seat`'s board replaced by `board`. */
  private def withBoard(seat: Seat, board: PlayerBoard): Battleship = seat match {
    case Player1 => copy(board1 = board)
    case Player2 => copy(board2 = board)
  }

  private def inBounds(c: Coord): Boolean =
    c.row >= 0 && c.row < Size && c.col >= 0 && c.col < Size

  /** Fires a shot for `player` at the target on the opponent's board.
    *
    * Validates turn order, board bounds, and that the cell has not already been targeted. On success records the shot
    * on the opponent's board and, if that sinks the opponent's entire fleet, marks `player` the winner. The turn passes
    * to the opponent after every shot, hit or miss.
    */
  def play(player: Seat, move: Fire): Either[GameError, Battleship] =
    if (gameStatus != InProgress)
      Left(GameOver)
    else if (player != currentPlayer)
      Left(InvalidTurn)
    else if (!inBounds(move.target))
      Left(OutOfBounds)
    else {
      val targetSeat = opponent(player)
      val targetBoard = boardFor(targetSeat)
      if (targetBoard.shots.contains(move.target))
        Left(AlreadyFired)
      else {
        val updatedBoard = targetBoard.copy(shots = targetBoard.shots + move.target)
        Right(
          withBoard(targetSeat, updatedBoard).copy(
            currentPlayer = opponent(player),
            winner = if (updatedBoard.allSunk) Some(player) else None
          )
        )
      }
    }

  /** Forfeits the game on behalf of the leaving player: the opponent is declared the winner.
    *
    * Rejects with [[core.GameError.InvalidPlayer]] if `playerId` is not a participant, or [[core.GameError.GameOver]]
    * if the game has already ended. The full state is retained, so the fog-of-war view layer is unaffected.
    */
  override def playerLeft(playerId: PlayerId): Either[GameError, Battleship] =
    playerFor(playerId) match {
      case None                                => Left(GameError.InvalidPlayer(playerId))
      case Some(_) if gameStatus != InProgress => Left(GameOver)
      case Some(leaver)                        => Right(copy(winner = Some(opponent(leaver))))
    }

  /** Renders both players' boards for logging: `S` ship, `X` hit, `o` miss, `.` empty water. */
  def render: String = {
    def renderBoard(b: PlayerBoard): String = {
      val shipCells = b.shipCells
      (0 until Size)
        .map { r =>
          (0 until Size).map { c =>
            (shipCells.contains(Coord(r, c)), b.shots.contains(Coord(r, c))) match {
              case (true, true)   => "X"
              case (true, false)  => "S"
              case (false, true)  => "o"
              case (false, false) => "."
            }
          }.mkString
        }
        .mkString("\n")
    }
    s"Player1:\n${renderBoard(board1)}\n\nPlayer2:\n${renderBoard(board2)}"
  }
}
