package com.andy327.persistence.db.postgres

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import com.zaxxer.hikari.HikariDataSource
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PostgresTransactorSpec extends AnyWordSpec with Matchers {

  implicit val runtime: IORuntime = IORuntime.global

  "PostgresTransactor.transactor" should {
    "execute a simple SELECT 1 query" in {
      val result: IO[Int] =
        PostgresTransactor.transactor.use { xa =>
          sql"select 1".query[Int].unique.transact(xa)
        }

      result.unsafeRunSync() shouldBe 1
    }

    "close the underlying HikariDataSource after release" in {
      // Acquire the resource and, inside the use block, hand the datasource outward
      val ds: HikariDataSource =
        PostgresTransactor.transactor.use { xa =>
          IO.pure(xa.kernel) // expose the HikariDataSource itself
        }.unsafeRunSync() // the Resource has been released

      ds.isClosed shouldBe true // closes almost instantly, no blocking
    }
  }
}
