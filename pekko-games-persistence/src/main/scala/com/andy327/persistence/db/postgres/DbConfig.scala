package com.andy327.persistence.db.postgres

/**
 * Represents the configuration settings required to establish a connection to a PostgreSQL database.
 */
final case class DbConfig(
    url: String,
    user: String,
    password: String
)
