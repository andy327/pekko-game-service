package com.andy327.server.actors.persistence

import scala.util.control.NoStackTrace

import cats.effect.IO

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameType}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository

class PostgresActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {
  class DummyRepo(
      loadResult: Either[Throwable, Option[Game[_, _, _, _, _]]] = Right(None),
      saveResult: Either[Throwable, Unit] = Right(())
  ) extends GameRepository {
    def loadGame(gameId: String, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.fromEither(loadResult)
    def saveGame(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = IO.fromEither(saveResult)
    def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] = IO.pure(Map.empty)
  }

  val loadError: RuntimeException with NoStackTrace = new RuntimeException("loading failure") with NoStackTrace
  val saveError: RuntimeException with NoStackTrace = new RuntimeException("saving failure") with NoStackTrace

  class ErrorRepo extends GameRepository { // throws synchronous error
    // throws when defining the IO, not when running it
    override def loadGame(gameId: String, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] = throw loadError
    override def saveGame(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = throw saveError
    override def loadAllGames(): IO[Map[String, (GameType, Game[_, _, _, _, _])]] = throw loadError
  }

  val freshGame: TicTacToe = TicTacToe.empty("alice", "bob")

  "PostgresActor" should {
    "reply with SnapshotLoaded on successful LoadSnapshot" in {
      val gameRepo = new DummyRepo(loadResult = Right(Some(freshGame)))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotLoaded]()
      persistActor ! PersistenceProtocol.LoadSnapshot("game-1", GameType.TicTacToe, replyProbe.ref)

      replyProbe.expectMessage(PersistenceProtocol.SnapshotLoaded(Right(Some(freshGame))))
    }

    "reply with SnapshotLoaded on failed LoadSnapshot when GameRepository throws an asynchronous error" in {
      val gameRepo = new DummyRepo(loadResult = Left(loadError))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotLoaded]()
      persistActor ! PersistenceProtocol.LoadSnapshot("game-2", GameType.TicTacToe, replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result.result.swap.getOrElse(fail("Expected a Left but got a Right")) shouldBe loadError
    }

    "reply with SnapshotLoaded on failed LoadSnapshot when GameRepository throws a synchronous error" in {
      val gameRepo = new ErrorRepo
      val actor = spawn(PostgresActor(gameRepo))
      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotLoaded]()
      actor ! PersistenceProtocol.LoadSnapshot("game-3", GameType.TicTacToe, replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result.result.swap.getOrElse(fail("Expected a Left but got a Right")) shouldBe loadError
    }

    "reply with SnapshotSaved on successful SaveSnapshot" in {
      val gameRepo = new DummyRepo(saveResult = Right(()))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot("game-4", GameType.TicTacToe, freshGame, replyProbe.ref)

      replyProbe.expectMessage(PersistenceProtocol.SnapshotSaved(Right(())))
    }

    "reply with SnapshotSaved on failed SaveSnapshot when GameRepository throws an asynchronous error" in {
      val gameRepo = new DummyRepo(saveResult = Left(saveError))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot("game-5", GameType.TicTacToe, freshGame, replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result.result.swap.getOrElse(fail("Expected a Left but got a Right")) shouldBe saveError
    }

    "reply with SnapshotSaved on failed SaveSnapshot when GameRepository throws a synchronous error" in {
      val gameRepo = new ErrorRepo
      val actor = spawn(PostgresActor(gameRepo))
      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      actor ! PersistenceProtocol.SaveSnapshot("game-6", GameType.TicTacToe, freshGame, replyProbe.ref)

      val result = replyProbe.receiveMessage()
      result.result.swap.getOrElse(fail("Expected a Left but got a Right")) shouldBe saveError
    }
  }
}
