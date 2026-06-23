package com.andy327.server.actors.persistence

import java.util.UUID
import java.util.concurrent.ConcurrentLinkedQueue

import scala.util.control.NoStackTrace

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import io.circe.syntax._
import org.apache.pekko.actor.testkit.typed.scaladsl.ScalaTestWithActorTestKit
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}
import com.andy327.model.tictactoe.TicTacToe
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult
import com.andy327.persistence.db.{
  GameRepository,
  MoveHistoryRepository,
  MoveRecord,
  PlayerGameRecord,
  PlayerHistoryRepository
}

class PostgresActorSpec extends ScalaTestWithActorTestKit with AnyWordSpecLike with Matchers {
  implicit val runtime: IORuntime = IORuntime.global

  class DummyRepo(saveResult: Either[Throwable, Unit] = Right(())) extends GameRepository {
    def initialize(): IO[Unit] = IO.unit
    def loadGame(gameId: GameId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] = IO.pure(None)
    def saveGame(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = IO.fromEither(saveResult)
    def loadAllGames(): IO[Map[GameId, (GameType, Game[_, _, _, _, _])]] = IO.pure(Map.empty)
  }

  /** Records each appended move; `appendResult` lets a test force the append IO to fail. */
  class RecordingMoveRepo(appendResult: Either[Throwable, Unit] = Right(())) extends MoveHistoryRepository {
    val appended = new ConcurrentLinkedQueue[(GameId, Int, PlayerId, Json)]()
    def initialize(): IO[Unit] = IO.unit
    def appendMove(gameId: GameId, seq: Int, playerId: PlayerId, move: Json): IO[Unit] =
      IO(appended.add((gameId, seq, playerId, move))) *> IO.fromEither(appendResult)
    def loadMoves(gameId: GameId): IO[List[MoveRecord]] = IO.pure(Nil)
  }

  /** Records each game-result write; `recordResult` lets a test force the record IO to fail. */
  class RecordingPlayerHistoryRepo(recordResult: Either[Throwable, Unit] = Right(())) extends PlayerHistoryRepository {
    val recorded = new ConcurrentLinkedQueue[(PlayerId, GameId, GameType, GameResult, Boolean)]()
    def initialize(): IO[Unit] = IO.unit
    def record(
        playerId: PlayerId,
        gameId: GameId,
        gameType: GameType,
        result: GameResult,
        forfeit: Boolean
    ): IO[Unit] =
      IO(recorded.add((playerId, gameId, gameType, result, forfeit))) *> IO.fromEither(recordResult)
    def findByPlayer(playerId: PlayerId): IO[List[PlayerGameRecord]] = IO.pure(Nil)
  }

  val saveError: RuntimeException with NoStackTrace = new RuntimeException("saving failure") with NoStackTrace
  val appendError: RuntimeException with NoStackTrace = new RuntimeException("append failure") with NoStackTrace
  val recordError: RuntimeException with NoStackTrace = new RuntimeException("record failure") with NoStackTrace

  val alice: PlayerId = UUID.randomUUID()
  val bob: PlayerId = UUID.randomUUID()
  val freshGame: TicTacToe = TicTacToe.empty(alice, bob)

  "PostgresActor" should {
    "reply with SnapshotSaved on successful SaveSnapshot" in {
      val persistActor = spawn(PostgresActor(new DummyRepo(saveResult = Right(())), new RecordingMoveRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot(UUID.randomUUID(), GameType.TicTacToe, freshGame, replyProbe.ref)

      replyProbe.expectMessage(PersistenceProtocol.SnapshotSaved(Right(())))
    }

    "reply with SnapshotSaved on failed SaveSnapshot when GameRepository raises an error" in {
      val persistActor = spawn(PostgresActor(new DummyRepo(saveResult = Left(saveError)), new RecordingMoveRepo))

      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot(UUID.randomUUID(), GameType.TicTacToe, freshGame, replyProbe.ref)

      val Left(err) = replyProbe.receiveMessage().result
      err shouldBe saveError
    }

    "append a move to the move repository on AppendMove" in {
      val moveRepo = new RecordingMoveRepo
      val persistActor = spawn(PostgresActor(new DummyRepo(), moveRepo))
      val gameId = UUID.randomUUID()
      val move = Json.obj("col" -> 3.asJson)

      persistActor ! PersistenceProtocol.AppendMove(gameId, 0, alice, move)

      createTestProbe().awaitAssert(moveRepo.appended should have size 1)
      moveRepo.appended.peek() shouldBe ((gameId, 0, alice, move))
    }

    "stay alive when a move append fails" in {
      val persistActor = spawn(PostgresActor(new DummyRepo(), new RecordingMoveRepo(appendResult = Left(appendError))))

      persistActor ! PersistenceProtocol.AppendMove(UUID.randomUUID(), 0, alice, Json.obj())

      // a failed append must not crash the actor — a subsequent snapshot still gets a reply
      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot(UUID.randomUUID(), GameType.TicTacToe, freshGame, replyProbe.ref)
      replyProbe.expectMessage(PersistenceProtocol.SnapshotSaved(Right(())))
    }

    "record a game result to the player-history repository on RecordGameResult" in {
      val historyRepo = new RecordingPlayerHistoryRepo
      val persistActor = spawn(PostgresActor(new DummyRepo(), new RecordingMoveRepo, historyRepo))
      val gameId = UUID.randomUUID()

      persistActor ! PersistenceProtocol.RecordGameResult(alice, gameId, GameType.TicTacToe, GameResult.Win, false)

      createTestProbe().awaitAssert(historyRepo.recorded should have size 1)
      historyRepo.recorded.peek() shouldBe ((alice, gameId, GameType.TicTacToe, GameResult.Win, false))
    }

    "stay alive when a game-result record fails" in {
      val historyRepo = new RecordingPlayerHistoryRepo(recordResult = Left(recordError))
      val persistActor = spawn(PostgresActor(new DummyRepo(), new RecordingMoveRepo, historyRepo))

      persistActor ! PersistenceProtocol.RecordGameResult(
        alice,
        UUID.randomUUID(),
        GameType.TicTacToe,
        GameResult.Loss,
        true
      )

      // a failed record must not crash the actor — a subsequent snapshot still gets a reply
      val replyProbe = createTestProbe[PersistenceProtocol.SnapshotSaved]()
      persistActor ! PersistenceProtocol.SaveSnapshot(UUID.randomUUID(), GameType.TicTacToe, freshGame, replyProbe.ref)
      replyProbe.expectMessage(PersistenceProtocol.SnapshotSaved(Right(())))
    }
  }
}
