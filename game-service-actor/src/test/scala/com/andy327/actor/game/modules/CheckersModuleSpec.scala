package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.checkers.CheckersActor
import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{CheckersState, GameOperation, GameRegistry, GameState, MovePayload}
import com.andy327.actor.lobby.Player
import com.andy327.model.checkers.{Black, Checkers, Move, Piece, Red, Square}
import com.andy327.model.core.{GameError, GameType}

class CheckersModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "CheckersModule" should {
    "successfully decode a valid Checkers move JSON" in {
      val json = """{"from":{"row":5,"col":2},"steps":[{"row":3,"col":4},{"row":1,"col":2}]}"""
      decode[MovePayload](json)(CheckersModule.moveDecoder) shouldBe
        Right(MovePayload.CheckersMove(Square(5, 2), List(Square(3, 4), Square(1, 2))))
    }

    "fail to decode an invalid Checkers move JSON" in {
      val json = """{"bad":"data"}"""
      decode[MovePayload](json)(CheckersModule.moveDecoder).isLeft shouldBe true
    }

    "convert a valid GameOperation.MakeMove to a GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val move = MovePayload.CheckersMove(Square(5, 2), List(Square(4, 1)))

      val result = CheckersModule.toGameCommand(GameOperation.MakeMove(alice.id, move), replyProbe.ref)

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, checkersMove, reply)) =>
          playerId shouldBe alice.id
          checkersMove shouldBe Move(Square(5, 2), List(Square(4, 1)))
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = CheckersModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()

      val result = CheckersActor.subscribeCommand(playerProbe.ref, playerId)

      result shouldBe TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a Checkers game to CheckersState, rendering kings uppercase and pawns lowercase" in {
      val alice = Player("alice")
      val bob = Player("bob")
      val empty = Vector.fill(Checkers.Size, Checkers.Size)(Option.empty[Piece])
      val withRedKing = empty.updated(0, empty(0).updated(1, Some(Piece(Red, isKing = true))))
      val board = withRedKing.updated(5, withRedKing(5).updated(0, Some(Piece(Black, isKing = false))))
      val game = Checkers(alice.id, bob.id, board, Red, None, moveCount = 0)

      val state = CheckersModule.serialize(game, None)
      state shouldBe a[CheckersState]
      val view = state.asInstanceOf[CheckersState]
      view.board(0)(1) shouldBe Some(Piece(Red, isKing = true)) // red king → uppercase
      view.board(5)(0) shouldBe Some(Piece(Black, isKing = false)) // black pawn → lowercase
      view.board(4)(4) shouldBe None // empty square
    }

    "tag the serialized view with the requesting player's own color" in {
      val alice = Player("alice") // seated Red (first)
      val bob = Player("bob") // seated Black (second)
      val game = Checkers.empty(alice.id, bob.id)

      CheckersModule.serialize(game, Some(alice.id)).asInstanceOf[CheckersState].viewerSeat shouldBe Some(Red)
      CheckersModule.serialize(game, Some(bob.id)).asInstanceOf[CheckersState].viewerSeat shouldBe Some(Black)
      CheckersModule.serialize(game, None).asInstanceOf[CheckersState].viewerSeat shouldBe None // spectator
    }

    "expose the Checkers bundle through the GameRegistry" in {
      val bundle = GameRegistry.forType(GameType.Checkers)
      bundle.module shouldBe CheckersModule
      bundle.actor shouldBe CheckersActor
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val unsupportedMove = null.asInstanceOf[MovePayload]

      val result = CheckersModule.toGameCommand(
        GameOperation.MakeMove(alice.id, unsupportedMove),
        replyProbe.ref
      )

      val Left(err) = result
      err.message should include("Unsupported move type for Checkers")
    }
  }
}
