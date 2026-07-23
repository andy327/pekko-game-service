package com.andy327.actor.game.modules

import java.util.UUID

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.actor.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.actor.game.{GameOperation, GameRegistry, GameView, MastermindView, MovePayload}
import com.andy327.actor.lobby.Player
import com.andy327.actor.mastermind.MastermindActor
import com.andy327.model.core.{GameError, GameType}
import com.andy327.model.mastermind.{Attempt, Codebreaker, Feedback, Guess, Mastermind, Peg, SetCode}

class MastermindModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "MastermindModule" should {
    "successfully decode a valid Mastermind move JSON" in {
      val json = """{"action":"setcode","pegs":["red","green","yellow","blue"]}"""
      decode[MovePayload](json)(MastermindModule.moveDecoder) shouldBe
        Right(MovePayload.MastermindAction("setcode", List("red", "green", "yellow", "blue")))
    }

    "fail to decode an invalid Mastermind move JSON" in {
      decode[MovePayload]("""{"bad":"data"}""")(MastermindModule.moveDecoder).isLeft shouldBe true
    }

    "convert a setcode operation to a SetCode GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val move = MovePayload.MastermindAction("setcode", List("red", "green", "yellow", "blue"))

      MastermindModule.toGameCommand(GameOperation.MakeMove(alice.id, move), replyProbe.ref) match {
        case Right(TurnBasedGameActor.MakeMove(playerId, setCode, reply)) =>
          playerId shouldBe alice.id
          setCode shouldBe SetCode(Vector(Peg.Red, Peg.Green, Peg.Yellow, Peg.Blue))
          reply shouldBe replyProbe.ref
        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert a guess operation to a Guess GameCommand" in {
      val bob = Player("bob")
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val move = MovePayload.MastermindAction("guess", List("red", "red", "blue", "green"))

      MastermindModule.toGameCommand(GameOperation.MakeMove(bob.id, move), replyProbe.ref) match {
        case Right(TurnBasedGameActor.MakeMove(_, guess, _)) =>
          guess shouldBe Guess(Vector(Peg.Red, Peg.Red, Peg.Blue, Peg.Green))
        case other => fail(s"Unexpected result: $other")
      }
    }

    "reject a move naming a color that is not in the palette" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val move = MovePayload.MastermindAction("guess", List("red", "chartreuse", "blue", "green"))

      val Left(err) = MastermindModule.toGameCommand(GameOperation.MakeMove(UUID.randomUUID(), move), replyProbe.ref)
      err.message should include("Invalid peg color: chartreuse")
    }

    "reject an unknown Mastermind action" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val move = MovePayload.MastermindAction("peek", List("red", "green", "yellow", "blue"))

      val Left(err) = MastermindModule.toGameCommand(GameOperation.MakeMove(UUID.randomUUID(), move), replyProbe.ref)
      err.message should include("Unknown Mastermind action: peek")
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      MastermindModule.toGameCommand(GameOperation.GetState, replyProbe.ref) shouldBe
        Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameView]]()
      val unsupportedMove = null.asInstanceOf[MovePayload]

      val Left(err) =
        MastermindModule.toGameCommand(GameOperation.MakeMove(UUID.randomUUID(), unsupportedMove), replyProbe.ref)
      err.message should include("Unsupported move type for Mastermind")
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()
      MastermindActor.subscribeCommand(playerProbe.ref, playerId) shouldBe
        TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a Mastermind game to MastermindView" in {
      MastermindModule.project(Mastermind.newGame(Seq(UUID.randomUUID(), UUID.randomUUID())), None) shouldBe
        a[MastermindView]
    }

    "keep the codebreaker's view free of the secret while play is ongoing" in {
      val codemaker = UUID.randomUUID()
      val codebreaker = UUID.randomUUID()
      val game = Mastermind(codemaker, codebreaker, Some(Vector(Peg.Red, Peg.Green, Peg.Yellow, Peg.Blue)), Nil, None)

      MastermindModule.project(game, Some(codebreaker)).asInstanceOf[MastermindView].secret shouldBe None
      MastermindModule.project(game, Some(codemaker)).asInstanceOf[MastermindView].secret shouldBe
        Some(Vector(Peg.Red, Peg.Green, Peg.Yellow, Peg.Blue))
    }

    "render guesses and the winner, and reveal the secret to everyone, once the game is over" in {
      val secret = Vector(Peg.Red, Peg.Green, Peg.Yellow, Peg.Blue)
      val finished =
        Mastermind(
          UUID.randomUUID(),
          UUID.randomUUID(),
          Some(secret),
          List(Attempt(secret, Feedback(4, 0))),
          Some(Codebreaker)
        )

      val state = MastermindModule.project(finished, None).asInstanceOf[MastermindView]
      state.guesses shouldBe List(Attempt(secret, Feedback(4, 0)))
      state.winner shouldBe Some(Codebreaker)
      state.secret shouldBe Some(secret) // revealed to all (a spectator here) once over
    }

    "expose the Mastermind bundle through the GameRegistry" in {
      val bundle = GameRegistry.forType(GameType.Mastermind)
      bundle.module shouldBe MastermindModule
      bundle.actor shouldBe MastermindActor
    }
  }
}
