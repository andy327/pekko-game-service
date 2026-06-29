package com.andy327.persistence.db.postgres

import java.util.UUID

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie.Transactor
import doobie.implicits._
import io.circe.Json
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.{MatchId, PlayerId}

class PostgresMoveHistoryRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  /** Single PostgreSQL container spun up once per ScalaTest run. */
  override val container: PostgreSQLContainer = PostgreSQLContainer()

  private lazy val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = container.jdbcUrl,
    user = container.username,
    password = container.password,
    None
  )

  private lazy val repo = new PostgresMoveHistoryRepository(xa)

  override def afterStart(): Unit =
    repo.initialize().unsafeRunSync()

  private def move(col: Int): Json = Json.obj("col" -> col.asJson)

  "PostgresMoveHistoryRepository" should {
    "append moves and load them ordered by seq" in {
      val matchId: MatchId = UUID.randomUUID()
      val alice: PlayerId = UUID.randomUUID()
      val bob: PlayerId = UUID.randomUUID()

      repo.appendMove(matchId, 0, alice, move(3)).unsafeRunSync()
      repo.appendMove(matchId, 1, bob, move(4)).unsafeRunSync()
      repo.appendMove(matchId, 2, alice, move(3)).unsafeRunSync()

      val moves = repo.loadMoves(matchId).unsafeRunSync()
      moves.map(_.seq) shouldBe List(0, 1, 2)
      moves.map(_.playerId) shouldBe List(alice, bob, alice)
      moves.map(_.move) shouldBe List(move(3), move(4), move(3))
      moves.foreach(_.createdAt should not be null)
    }

    "order by seq even when moves are appended out of order" in {
      val matchId: MatchId = UUID.randomUUID()
      val player: PlayerId = UUID.randomUUID()

      repo.appendMove(matchId, 2, player, move(2)).unsafeRunSync()
      repo.appendMove(matchId, 0, player, move(0)).unsafeRunSync()
      repo.appendMove(matchId, 1, player, move(1)).unsafeRunSync()

      repo.loadMoves(matchId).unsafeRunSync().map(_.seq) shouldBe List(0, 1, 2)
    }

    "return an empty list for a game with no recorded moves" in {
      repo.loadMoves(UUID.randomUUID()).unsafeRunSync() shouldBe empty
    }

    "ignore a duplicate (matchId, seq) rather than failing" in {
      val matchId: MatchId = UUID.randomUUID()
      val player: PlayerId = UUID.randomUUID()

      repo.appendMove(matchId, 0, player, move(1)).unsafeRunSync()
      repo.appendMove(matchId, 0, player, move(9)).unsafeRunSync() // same seq — must not overwrite or throw

      val moves = repo.loadMoves(matchId).unsafeRunSync()
      moves.map(_.move) shouldBe List(move(1))
    }

    "skip rows whose stored move JSON cannot be parsed" in {
      val matchId: MatchId = UUID.randomUUID()
      val player: PlayerId = UUID.randomUUID()

      repo.appendMove(matchId, 0, player, move(1)).unsafeRunSync()
      // a corrupt row inserted directly, bypassing appendMove (which always writes valid JSON)
      sql"""
        INSERT INTO game_moves (game_id, seq, player_id, move_json)
        VALUES (${matchId.toString}, 1, ${player.toString}, 'not-json')
      """.update.run.transact(xa).unsafeRunSync()

      val moves = repo.loadMoves(matchId).unsafeRunSync()
      moves.map(_.seq) shouldBe List(0) // the corrupt seq-1 row is skipped
    }

    "isolate move logs by matchId" in {
      val game1: MatchId = UUID.randomUUID()
      val game2: MatchId = UUID.randomUUID()
      val player: PlayerId = UUID.randomUUID()

      repo.appendMove(game1, 0, player, move(1)).unsafeRunSync()
      repo.appendMove(game2, 0, player, move(2)).unsafeRunSync()

      repo.loadMoves(game1).unsafeRunSync().map(_.move) shouldBe List(move(1))
      repo.loadMoves(game2).unsafeRunSync().map(_.move) shouldBe List(move(2))
    }
  }
}
