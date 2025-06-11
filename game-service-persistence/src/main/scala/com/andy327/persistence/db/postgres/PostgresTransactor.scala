package com.andy327.persistence.db.postgres

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.{IO, Resource}

import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts

/**
 * Creates a Resource that manages a Doobie HikariTransactor.
 */
object PostgresTransactor {
  def apply(config: Config = ConfigFactory.load()): Resource[IO, HikariTransactor[IO]] = {
    val dbConfig = config.getConfig("pekko-game-service.db")

    val url = dbConfig.getString("url")
    val user = dbConfig.getString("user")
    val pass = dbConfig.getString("pass")
    val poolSize = dbConfig.getInt("poolSize")

    for {
      ce <- ExecutionContexts.fixedThreadPool[IO](poolSize)
      xa <- HikariTransactor.newHikariTransactor[IO](
        driverClassName = "org.postgresql.Driver",
        url = url,
        user = user,
        pass = pass,
        connectEC = ce
      )
    } yield xa
  }
}
