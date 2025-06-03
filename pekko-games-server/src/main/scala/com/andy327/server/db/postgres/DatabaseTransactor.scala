package com.andy327.server.db.postgres

import cats.effect._
import doobie.hikari._
import doobie.util.ExecutionContexts

import com.typesafe.config.ConfigFactory

object DatabaseTransactor {
  private val config = ConfigFactory.load()
  private val dbConfig = config.getConfig("pekko-games.db")

  private val url = dbConfig.getString("url")
  private val user = dbConfig.getString("user")
  private val pass = dbConfig.getString("pass")
  private val poolSize = dbConfig.getInt("poolSize")

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
