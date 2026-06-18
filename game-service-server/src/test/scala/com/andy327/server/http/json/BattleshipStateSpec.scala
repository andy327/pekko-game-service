package com.andy327.server.http.json

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Seat, Ship}
import com.andy327.model.core.PlayerId

class BattleshipStateSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  // board1: a 2-cell ship at (0,0)-(0,1); P2 has fired at (0,0) [hit] and (9,9) [miss]
  private val board1 = PlayerBoard(List(Ship(Set(Coord(0, 0), Coord(0, 1)))), Set(Coord(0, 0), Coord(9, 9)))
  // board2: a 1-cell ship at (5,5); P1 has fired at (5,5) [hit]
  private val board2 = PlayerBoard(List(Ship(Set(Coord(5, 5)))), Set(Coord(5, 5)))

  private def game(currentPlayer: Seat = Player1, winner: Option[Seat] = None): Battleship =
    Battleship(alice, bob, board1, board2, currentPlayer, winner)

  "BattleshipState.of for the owning player" should {
    "reveal that player's own board fully and fog the opponent's" in {
      val view = BattleshipState.of(game(), Some(Player1))
      view.viewerSeat shouldBe Some("P1")

      // own board (board1) is fully revealed
      view.board1(0)(0) shouldBe "hit" // ship that was hit
      view.board1(0)(1) shouldBe "ship" // un-hit ship
      view.board1(9)(9) shouldBe "miss" // empty cell that was fired at
      view.board1(2)(2) shouldBe "water" // empty, un-fired

      // opponent board (board2) is fog-of-war: resolved shots show, un-fired cells stay unknown, ships hidden
      view.board2(5)(5) shouldBe "hit"
      view.board2.flatten should contain("unknown")
      view.board2.flatten should not contain "ship"
      view.board2.flatten should not contain "water"
    }
  }

  "BattleshipState.of for a spectator" should {
    "fog both boards, hiding every un-hit ship" in {
      val view = BattleshipState.of(game(), None)
      view.viewerSeat shouldBe None

      view.board1.flatten should not contain "ship"
      view.board2.flatten should not contain "ship"

      // resolved shots are still visible to everyone
      view.board1(0)(0) shouldBe "hit"
      view.board1(9)(9) shouldBe "miss"
      view.board2(5)(5) shouldBe "hit"

      // un-fired cells (including the un-hit ship cell at (0,1)) are hidden
      view.board1(0)(1) shouldBe "unknown"
      view.board1.flatten should contain("unknown")
    }
  }

  "BattleshipState.of" should {
    "report the current player and winner as seat symbols" in {
      val view = BattleshipState.of(game(currentPlayer = Player2, winner = Some(Player1)), Some(Player2))
      view.currentPlayer shouldBe "P2"
      view.winner shouldBe Some("P1")
      view.viewerSeat shouldBe Some("P2")
    }
  }
}
