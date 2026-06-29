package com.andy327.persistence.db

import java.util.UUID

import cats.effect.unsafe.implicits.global

import io.circe.Json
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class NoOpMoveHistoryRepositorySpec extends AnyWordSpec with Matchers {

  "NoOpMoveHistoryRepository" should {
    "do nothing on initialize and appendMove, and return no history" in {
      val repo = NoOpMoveHistoryRepository
      val matchId = UUID.randomUUID()

      repo.initialize().unsafeRunSync() shouldBe (())
      repo.appendMove(matchId, 0, UUID.randomUUID(), Json.obj()).unsafeRunSync() shouldBe (())
      repo.loadMoves(matchId).unsafeRunSync() shouldBe empty
    }
  }
}
