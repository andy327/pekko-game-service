package com.andy327.server.actors.core

import cats.effect.IO

import org.apache.pekko.actor.testkit.typed.scaladsl.{ActorTestKit, TestProbe}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository
import com.andy327.server.actors.persistence.PersistenceProtocol

/** In‑memory GameRepository for unit tests */
class InMemRepo extends GameRepository {
  private val db = scala.collection.concurrent.TrieMap.empty[String, (GameType, Game[_, _, _, _, _])]

  def saveGame(id: String, tpe: GameType, g: Game[_, _, _, _, _]): IO[Unit] =
    IO(db.update(id, (tpe, g)))

  def loadGame(id: String, tpe: GameType): IO[Option[Game[_, _, _, _, _]]] =
    IO(db.get(id).collect { case (`tpe`, g) => g })

  def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] =
    IO(db.toMap)
}

class GameManagerSpec extends AnyWordSpecLike with Matchers {
  private val testKit = ActorTestKit()
  import testKit._

  "GameManager" should {
    "spawn a game actor and persist an empty snapshot" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val readyProbe = TestProbe[GameManager.Ready.type]()

      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready) // wait for initialization

      val replyProbe = TestProbe[String]()
      gm ! GameManager.CreateGame(GameType.TicTacToe, Seq("alice", "bob"), replyProbe.ref)

      val gameId = replyProbe.receiveMessage()
      gameId.length should be > 0

      val save = persistProbe.expectMessageType[PersistenceProtocol.SaveSnapshot]
      save.gameId shouldBe gameId
      save.gameType shouldBe GameType.TicTacToe
      save.game shouldBe TicTacToe.empty("alice", "bob")
    }

    "return error when forwarding to unknown game" in {
      val persistProbe = TestProbe[PersistenceProtocol.Command]()
      val gameRepo = new InMemRepo
      val readyProbe = TestProbe[GameManager.Ready.type]()
      val gm = spawn(GameManager(persistProbe.ref, gameRepo, Some(readyProbe.ref)))
      readyProbe.expectMessage(GameManager.Ready) // wait for initialization

      val replyProbe = TestProbe[GameManager.GameResponse]()
      gm ! GameManager.ForwardToGame("no-such-id", "noop‑msg", Some(replyProbe.ref))

      val error = replyProbe.expectMessageType[GameManager.ErrorResponse]
      error.message should include("No game found with gameId")
    }
  }
}
