package com.andy327.server.actors.persistence

import java.util.UUID

import scala.util.control.NoStackTrace

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.GameRepository

class PostgresActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {
  implicit val runtime: IORuntime = IORuntime.global

  class DummyRepo(
      loadResult: Either[Throwable, Option[Game[_, _, _, _, _]]] = Right(None),
      saveResult: Either[Throwable, Unit] = Right(())
  ) extends GameRepository {
    def initialize(): IO[Unit] = IO.unit
    def loadGame(gameId: GameId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.fromEither(loadResult)
    def saveGame(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = IO.fromEither(saveResult)
    def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] = IO.pure(Map.empty)
  }

  val loadError: RuntimeException with NoStackTrace = new RuntimeException("loading failure") with NoStackTrace
  val saveError: RuntimeException with NoStackTrace = new RuntimeException("saving failure") with NoStackTrace

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()
  val freshGame: TicTacToe = TicTacToe.empty(alice, bob)

  "PostgresActor" should {
    "reply with SnapshotLoaded on successful LoadSnapshot" in {
      val gameRepo = new DummyRepo(loadResult = Right(Some(freshGame)))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotLoaded]()
      persistActor ! PersistenceProtocol.LoadSnapshot(UUID.randomUUID(), GameType.TicTacToe, replyProbe.ref)

      replyProbe.expectMessage(PersistenceProtocol.SnapshotLoaded(Right(Some(freshGame))))
    }

    "reply with SnapshotLoaded on failed LoadSnapshot when GameRepository raises an error" in {
      val gameRepo = new DummyRepo(loadResult = Left(loadError))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotLoaded]()
      persistActor ! PersistenceProtocol.LoadSnapshot(UUID.randomUUID(), GameType.TicTacToe, replyProbe.ref)

      val Left(err) = replyProbe.receiveMessage().result
      err shouldBe loadError
    }

    "reply with SnapshotSaved on successful SaveSnapshot" in {
      val gameRepo = new DummyRepo(saveResult = Right(()))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot(UUID.randomUUID(), GameType.TicTacToe, freshGame, replyProbe.ref)

      replyProbe.expectMessage(PersistenceProtocol.SnapshotSaved(Right(())))
    }

    "reply with SnapshotSaved on failed SaveSnapshot when GameRepository raises an error" in {
      val gameRepo = new DummyRepo(saveResult = Left(saveError))
      val persistActor = spawn(PostgresActor(gameRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot(UUID.randomUUID(), GameType.TicTacToe, freshGame, replyProbe.ref)

      val Left(err) = replyProbe.receiveMessage().result
      err shouldBe saveError
    }

  }
}
