package com.andy327.model.battleship

import java.util.UUID

import scala.util.Random

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameError.{GameOver, InvalidTurn}
import com.andy327.model.core.{InProgress, PlayerId, Won}

class BattleshipSpec extends AnyWordSpec with Matchers {
  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()

  /** Builds a game with explicit boards so play logic can be tested without random placement. */
  private def gameWith(
      board1: PlayerBoard,
      board2: PlayerBoard,
      currentPlayer: Seat = Player1,
      winner: Option[Seat] = None
  ): Battleship =
    Battleship(alice, bob, board1, board2, currentPlayer, winner)

  private def board(cells: Set[Coord]*): PlayerBoard = PlayerBoard(cells.toList.map(Ship), Set.empty)

  "A Battleship game" should {
    "resolve participants to their seats with playerFor" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      game.playerFor(alice) shouldBe Some(Player1)
      game.playerFor(bob) shouldBe Some(Player2)
      game.playerFor(UUID.randomUUID()) shouldBe None
    }

    "list its roster in seat order with players" in {
      gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0)))).players shouldBe List(alice, bob)
    }

    "record a miss, pass the turn, and leave the game in progress" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      val Right(next) = game.play(Player1, Fire(Coord(5, 5)))
      next.board2.shots shouldBe Set(Coord(5, 5))
      next.currentPlayer shouldBe Player2
      next.winner shouldBe None
      next.gameStatus shouldBe InProgress
      next.moveCount shouldBe 1
    }

    "record a hit without ending the game when the ship is not fully sunk" in {
      val game = gameWith(board(Set(Coord(9, 9))), board(Set(Coord(0, 0), Coord(0, 1))))
      val Right(next) = game.play(Player1, Fire(Coord(0, 0)))
      next.board2.shots shouldBe Set(Coord(0, 0))
      next.winner shouldBe None
      next.gameStatus shouldBe InProgress
    }

    "declare the firer the winner once the opponent's whole fleet is sunk" in {
      val game = gameWith(board(Set(Coord(9, 9))), board(Set(Coord(0, 0))))
      val Right(next) = game.play(Player1, Fire(Coord(0, 0)))
      next.winner shouldBe Some(Player1)
      next.gameStatus shouldBe Won(Player1)
    }

    "reject a shot from the player whose turn it is not" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      game.play(Player2, Fire(Coord(0, 0))) shouldBe Left(InvalidTurn)
    }

    "reject a shot outside the board" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      game.play(Player1, Fire(Coord(Battleship.Size, 0))) shouldBe Left(OutOfBounds)
      game.play(Player1, Fire(Coord(-1, 0))) shouldBe Left(OutOfBounds)
    }

    "reject firing at a coordinate that has already been targeted" in {
      val game = gameWith(board(Set(Coord(0, 0), Coord(0, 1))), board(Set(Coord(0, 0), Coord(0, 1))))
      val afterP1 = game.play(Player1, Fire(Coord(0, 0))).toOption.get // hit on board2, not sunk
      val afterP2 = afterP1.play(Player2, Fire(Coord(5, 5))).toOption.get // miss on board1, turn back to P1
      afterP2.play(Player1, Fire(Coord(0, 0))) shouldBe Left(AlreadyFired)
    }

    "reject any further shot once the game is over" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))), winner = Some(Player1))
      game.play(Player1, Fire(Coord(1, 1))) shouldBe Left(GameOver)
    }

    "count shots across both boards in moveCount" in {
      val game = gameWith(board(Set(Coord(0, 0), Coord(0, 1))), board(Set(Coord(0, 0), Coord(0, 1))))
      val afterP1 = game.play(Player1, Fire(Coord(2, 2))).toOption.get
      val afterP2 = afterP1.play(Player2, Fire(Coord(3, 3))).toOption.get
      afterP2.moveCount shouldBe 2
    }

    "render hits, ships, misses, and empty water for both boards" in {
      val b1 = PlayerBoard(List(Ship(Set(Coord(0, 0), Coord(0, 1)))), Set(Coord(0, 0), Coord(5, 5)))
      val rendered = gameWith(b1, board(Set(Coord(9, 9)))).render
      rendered should include("Player1:")
      rendered should include("Player2:")
      rendered should include("XS........") // (0,0) hit, (0,1) unhit ship, rest empty water
      rendered should include("o") // the miss at (5,5)
    }
  }

  "A Battleship Seat" should {
    "render with a short label" in {
      Player1.toString shouldBe "P1"
      Player2.toString shouldBe "P2"
    }
  }

  "legalMoves" should {
    "offer every cell of the opponent's board before any shot is fired" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      game.legalMoves should have size (Battleship.Size * Battleship.Size).toLong
    }

    "exclude cells the current player has already fired at" in {
      // Player1's shots are recorded on board2; (3,3) and (7,2) have been fired at
      val game = gameWith(
        board(Set(Coord(0, 0))),
        PlayerBoard(List(Ship(Set(Coord(0, 0)))), Set(Coord(3, 3), Coord(7, 2)))
      )
      game.legalMoves should have size (Battleship.Size * Battleship.Size - 2).toLong
      game.legalMoves should not contain Fire(Coord(3, 3))
      game.legalMoves should not contain Fire(Coord(7, 2))
    }

    "accept exactly the moves listed, and reject a repeated shot both ways" in {
      val fired = Coord(3, 3)
      val game = gameWith(
        board(Set(Coord(0, 0))),
        PlayerBoard(List(Ship(Set(Coord(0, 0)))), Set(fired))
      )
      game.legalMoves.take(5).foreach(fire => game.play(Player1, fire) shouldBe a[Right[_, _]])
      game.legalMoves should not contain Fire(fired)
      game.play(Player1, Fire(fired)) shouldBe Left(AlreadyFired)
    }

    "offer no moves once the game is over" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))), winner = Some(Player1))
      game.legalMoves shouldBe empty
    }
  }

  "A leaving player" should {
    "forfeit to the opponent" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      game.playerLeft(alice).map(_.gameStatus) shouldBe Right(Won(Player2))
      game.playerLeft(bob).map(_.gameStatus) shouldBe Right(Won(Player1))
    }

    "be rejected for a non-participant" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))))
      game.playerLeft(UUID.randomUUID()) shouldBe a[Left[_, _]]
    }

    "be rejected once the game is already over" in {
      val game = gameWith(board(Set(Coord(0, 0))), board(Set(Coord(0, 0))), winner = Some(Player1))
      game.playerLeft(bob) shouldBe Left(GameOver)
    }
  }

  "Battleship.random" should {
    "start a fresh game with Player1 to move, no winner, and no shots fired" in {
      val game = Battleship.random(alice, bob, new Random(7))
      game.currentPlayer shouldBe Player1
      game.winner shouldBe None
      game.gameStatus shouldBe InProgress
      game.moveCount shouldBe 0
      game.board1.shots shouldBe empty
      game.board2.shots shouldBe empty
    }

    "place valid, non-overlapping, in-bounds fleets across many seeds (exercising placement retries)" in
      (0 until 50).foreach { seed =>
        val game = Battleship.random(alice, bob, new Random(seed.toLong))
        List(game.board1, game.board2).foreach { b =>
          withClue(s"seed $seed: ") {
            b.ships.map(_.cells.size).sorted shouldBe Battleship.ShipSizes.sorted
            val cells = b.ships.flatMap(_.cells)
            cells.size shouldBe cells.distinct.size // no overlaps
            cells.foreach { c =>
              c.row should ((be >= 0).and(be < Battleship.Size))
              c.col should ((be >= 0).and(be < Battleship.Size))
            }
          }
        }
      }
  }
}
