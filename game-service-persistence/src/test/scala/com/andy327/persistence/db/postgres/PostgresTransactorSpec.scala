package com.andy327.persistence.db.postgres

import com.typesafe.config.ConfigFactory

import cats.effect.unsafe.implicits.global
import cats.effect.{IO, Resource}

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie.hikari.HikariTransactor
import doobie.implicits._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PostgresTransactorSpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  /** Single PostgreSQL container spun up once per ScalaTest run. */
  override val container: PostgreSQLContainer = PostgreSQLContainer()

  "PostgresTransactor" should {
    "create a working transactor using a config" in {
      val config = ConfigFactory.parseString(s"""
        pekko-game-service.db {
          url = "${container.jdbcUrl}"
          user = "${container.username}"
          pass = "${container.password}"
          poolSize = 4
        }
      """)

      val transactorResource: Resource[IO, HikariTransactor[IO]] = PostgresTransactor(config)

      transactorResource.use { xa =>
        sql"SELECT 1".query[Int].unique.transact(xa).map { result =>
          result shouldBe 1
        }
      }.unsafeRunSync()
    }
  }
}
