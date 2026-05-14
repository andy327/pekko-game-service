package com.andy327.persistence.db.postgres

import com.typesafe.config.{Config, ConfigFactory}

import cats.effect.{IO, Resource}

import doobie.hikari.HikariTransactor
import doobie.util.ExecutionContexts

/** Builds a managed Doobie `HikariTransactor` from application config.
  *
  * Connection settings are read from `pekko-game-service.db`: `url`, `user`, `pass`, and `poolSize`. The returned
  * `Resource` handles pool lifecycle — acquire on open, shutdown on release.
  */
object PostgresTransactor {

  /** @param config Typesafe Config to read database settings from; defaults to the application classpath config */
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
