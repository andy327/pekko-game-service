package com.andy327.server.game.modules

import java.util.UUID

import scala.util.Random

import io.circe.parser.decode
import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.battleship.{Battleship, Coord, Fire}
import com.andy327.model.core.GameError
import com.andy327.server.actors.battleship.BattleshipActor
import com.andy327.server.actors.core.{PlayerActor, TurnBasedGameActor}
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.{BattleshipState, GameState}
import com.andy327.server.lobby.Player

class BattleshipModuleSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "BattleshipModule" should {
    "successfully decode a valid Battleship move JSON" in {
      val json = """{"row":1,"col":2}"""
      decode[MovePayload](json)(BattleshipModule.moveDecoder) shouldBe Right(MovePayload.BattleshipMove(1, 2))
    }

    "fail to decode an invalid Battleship move JSON" in {
      val json = """{"bad":"data"}"""
      decode[MovePayload](json)(BattleshipModule.moveDecoder).isLeft shouldBe true
    }

    "convert a valid GameOperation.MakeMove to a Fire GameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val move = MovePayload.BattleshipMove(3, 4)

      val result = BattleshipModule.toGameCommand(GameOperation.MakeMove(alice.id, move), replyProbe.ref)

      result match {
        case Right(TurnBasedGameActor.MakeMove(playerId, fire, reply)) =>
          playerId shouldBe alice.id
          fire shouldBe Fire(Coord(3, 4))
          reply shouldBe replyProbe.ref

        case other => fail(s"Unexpected result: $other")
      }
    }

    "convert GetState to a GetState GameCommand" in {
      val replyProbe = TestProbe[Either[GameError, GameState]]()

      val result = BattleshipModule.toGameCommand(GameOperation.GetState, replyProbe.ref)

      result shouldBe Right(TurnBasedGameActor.GetState(replyProbe.ref))
    }

    "produce a Subscribe command for a given PlayerActor ref" in {
      val playerProbe = TestProbe[PlayerActor.Command]()
      val playerId = UUID.randomUUID()

      val result = BattleshipActor.subscribeCommand(playerProbe.ref, playerId)

      result shouldBe TurnBasedGameActor.Subscribe(playerProbe.ref, playerId)
    }

    "serialize a Battleship game to BattleshipState" in {
      val game = Battleship.random(Player("alice").id, Player("bob").id, new Random(0))
      BattleshipModule.serialize(game, None) shouldBe a[BattleshipState]
    }

    "return error when passing unsupported MovePayload to toGameCommand" in {
      val alice = Player("alice")
      val replyProbe = TestProbe[Either[GameError, GameState]]()
      val unsupportedMove = null.asInstanceOf[MovePayload] // simulate invalid move type

      val result = BattleshipModule.toGameCommand(
        GameOperation.MakeMove(alice.id, unsupportedMove),
        replyProbe.ref
      )

      val Left(err) = result
      err.message should include("Unsupported move type for Battleship")
    }
  }
}
