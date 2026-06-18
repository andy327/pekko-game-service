package com.andy327.model

package object battleship {

  /** A zero-based (row, col) coordinate on a player's board, where (0,0) is the top-left cell. */
  case class Coord(row: Int, col: Int)

  /** A single ship, identified by the set of cells it occupies. */
  case class Ship(cells: Set[Coord])

  /** One player's own waters: the ships they placed and the cells the opponent has fired at.
    *
    * @param ships the player's fleet
    * @param shots the cells the opponent has fired at on this board (hit if they coincide with a ship cell)
    */
  case class PlayerBoard(ships: List[Ship], shots: Set[Coord]) {

    /** Every cell occupied by this player's fleet. */
    def shipCells: Set[Coord] = ships.flatMap(_.cells).toSet

    /** True once every ship cell has been hit — i.e. the whole fleet is sunk. */
    def allSunk: Boolean = shipCells.subsetOf(shots)
  }

  /** Identifies which of the two players a seat belongs to. Player1 fires first. */
  sealed trait Seat {
    def symbol: String
    override def toString: String = symbol
  }
  case object Player1 extends Seat { val symbol = "P1" }
  case object Player2 extends Seat { val symbol = "P2" }

  /** A move in Battleship: fire a shot at `target` on the opponent's board. */
  case class Fire(target: Coord)
}
