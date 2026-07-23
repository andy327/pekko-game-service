package com.andy327.server.http.json

import java.util.UUID

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.game.MovePayload.BattleshipMove
import com.andy327.actor.game.{BattleshipCell, BattleshipView}
import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Seat, Ship}
import com.andy327.model.core.PlayerId

class BattleshipViewSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  // board1: a 2-cell ship at (0,0)-(0,1); P2 has fired at (0,0) [hit] and (9,9) [miss]
  private val board1 = PlayerBoard(List(Ship(Set(Coord(0, 0), Coord(0, 1)))), Set(Coord(0, 0), Coord(9, 9)))
  // board2: a 1-cell ship at (5,5); P1 has fired at (5,5) [hit]
  private val board2 = PlayerBoard(List(Ship(Set(Coord(5, 5)))), Set(Coord(5, 5)))

  private def game(currentPlayer: Seat = Player1, winner: Option[Seat] = None): Battleship =
    Battleship(alice, bob, board1, board2, currentPlayer, winner)

  "BattleshipView.of for the owning player" should {
    "reveal that player's own board fully and fog the opponent's" in {
      val view = BattleshipView.of(game(), Some(Player1))
      view.viewerSeat shouldBe Some(Player1)

      // own board (board1) is fully revealed
      view.board1(0)(0) shouldBe BattleshipCell.Hit // ship that was hit
      view.board1(0)(1) shouldBe BattleshipCell.Ship // un-hit ship
      view.board1(9)(9) shouldBe BattleshipCell.Miss // empty cell that was fired at
      view.board1(2)(2) shouldBe BattleshipCell.Water // empty, un-fired

      // opponent board (board2) is fog-of-war: resolved shots show, un-fired cells stay unknown, ships hidden
      view.board2(5)(5) shouldBe BattleshipCell.Hit
      view.board2.flatten should contain(BattleshipCell.Unknown)
      view.board2.flatten should not contain BattleshipCell.Ship
      view.board2.flatten should not contain BattleshipCell.Water
    }

    "offer exactly the opponent cells still unknown to the viewer as their legal shots" in {
      val view = BattleshipView.of(game(), Some(Player1)) // Player1 is to act
      val unknownCells = for {
        row <- 0 until Battleship.Size
        col <- 0 until Battleship.Size
        if view.board2(row)(col) == BattleshipCell.Unknown
      } yield BattleshipMove(row, col)

      // the viewer's options are derivable from their own fogged projection — nothing hidden is consulted
      view.legalMoves should contain theSameElementsAs unknownCells
      view.legalMoves should have size (Battleship.Size * Battleship.Size - board2.shots.size).toLong
    }
  }

  "BattleshipView.of for a spectator" should {
    "fog both boards, hiding every un-hit ship" in {
      val view = BattleshipView.of(game(), None)
      view.viewerSeat shouldBe None

      view.board1.flatten should not contain BattleshipCell.Ship
      view.board2.flatten should not contain BattleshipCell.Ship

      // resolved shots are still visible to everyone
      view.board1(0)(0) shouldBe BattleshipCell.Hit
      view.board1(9)(9) shouldBe BattleshipCell.Miss
      view.board2(5)(5) shouldBe BattleshipCell.Hit

      // un-fired cells (including the un-hit ship cell at (0,1)) are hidden
      view.board1(0)(1) shouldBe BattleshipCell.Unknown
      view.board1.flatten should contain(BattleshipCell.Unknown)
    }

    "offer no moves to a spectator or to the player waiting on their opponent" in {
      BattleshipView.of(game(), None).legalMoves shouldBe empty
      BattleshipView.of(game(), Some(Player2)).legalMoves shouldBe empty // Player1 is to act
    }
  }

  "BattleshipView.of" should {
    "report the current player and winner as seats" in {
      val view = BattleshipView.of(game(currentPlayer = Player2, winner = Some(Player1)), Some(Player2))
      view.currentPlayer shouldBe Player2
      view.winner shouldBe Some(Player1)
      view.viewerSeat shouldBe Some(Player2)
    }
  }
}
