package com.andy327.persistence.db.postgres

import com.typesafe.config.ConfigFactory

import cats.effect._

import doobie.hikari._
import doobie.util.ExecutionContexts

object DatabaseTransactor {
  private val config = ConfigFactory.load()
  private val dbConfig = config.getConfig("pekko-game-service.db")

  private val url = dbConfig.getString("url")
  private val user = dbConfig.getString("user")
  private val pass = dbConfig.getString("pass")

  /**
   * Creates a Resource that manages a Doobie HikariTransactor.
   */
  def transactor: Resource[IO, HikariTransactor[IO]] = for {
    ce <- ExecutionContexts.fixedThreadPool[IO](8) // fixed thread pool for JDBC operations (used for blocking calls)
    xa <- HikariTransactor.newHikariTransactor[IO]( // HikariCP transactor for PostgreSQL
      driverClassName = "org.postgresql.Driver",
      url = url,
      user = user,
      pass = pass,
      connectEC = ce
    )
  } yield xa
}
