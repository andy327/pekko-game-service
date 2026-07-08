package com.andy327.persistence.db.postgres

import java.time.Instant
import java.util.UUID

import cats.effect.IO

import doobie._
import doobie.implicits._
import doobie.postgres._
import doobie.postgres.implicits._

import com.andy327.persistence.db.UserRepository.CreateError
import com.andy327.persistence.db.{Account, UserRepository}

/** [[UserRepository]] backed by PostgreSQL via Doobie.
  *
  * Each account is one row in `accounts`, keyed by a server-generated UUID, with case-insensitive email uniqueness
  * enforced by a unique index on `lower(email)`.
  */
class PostgresUserRepository(xa: Transactor[IO]) extends UserRepository {

  /** Tuple shape of an `accounts` row, mapped to [[Account]] without relying on case-class derivation. */
  private type Row = (UUID, String, String, Option[String], Instant, Option[Instant])
  private def toAccount(row: Row): Account = Account(row._1, row._2, row._3, row._4, row._5, row._6)

  /** Creates the 'accounts' table and case-insensitive email uniqueness index if they don't already exist, and adds
    * the `email_verified_at` column to a table predating it (an additive migration, safe to run every startup).
    */
  override def initialize(): IO[Unit] =
    (for {
      _ <- sql"""
        CREATE TABLE IF NOT EXISTS accounts (
          id            UUID PRIMARY KEY,
          username      TEXT NOT NULL,
          email         TEXT NOT NULL,
          password_hash TEXT,
          created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
        )
      """.update.run
      _ <- sql"""
        ALTER TABLE accounts ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMPTZ
      """.update.run
      _ <- sql"""
        CREATE UNIQUE INDEX IF NOT EXISTS accounts_email_lower_idx ON accounts (lower(email))
      """.update.run
    } yield ()).transact(xa)

  override def findById(id: UUID): IO[Option[Account]] =
    sql"SELECT id, username, email, password_hash, created_at, email_verified_at FROM accounts WHERE id = $id"
      .query[Row]
      .option
      .transact(xa)
      .map(_.map(toAccount))

  override def findByEmail(email: String): IO[Option[Account]] =
    sql"""SELECT id, username, email, password_hash, created_at, email_verified_at
          FROM accounts WHERE lower(email) = lower($email)"""
      .query[Row]
      .option
      .transact(xa)
      .map(_.map(toAccount))

  /** Inserts a new account. The `lower(email)` unique index makes a duplicate email fail atomically; that failure is
    * caught by SQL state and mapped to [[com.andy327.persistence.db.UserRepository.CreateError.EmailAlreadyExists]]
    * rather than propagating as an error.
    */
  override def create(
      username: String,
      email: String,
      passwordHash: Option[String]
  ): IO[Either[CreateError, Account]] = {
    val id = UUID.randomUUID()

    sql"""
      INSERT INTO accounts (id, username, email, password_hash)
      VALUES ($id, $username, $email, $passwordHash)
    """.update
      .withUniqueGeneratedKeys[Instant]("created_at")
      .attemptSomeSqlState { case sqlstate.class23.UNIQUE_VIOLATION => CreateError.EmailAlreadyExists }
      .map(_.map(createdAt => Account(id, username, email, passwordHash, createdAt, emailVerifiedAt = None)))
      .transact(xa)
  }

  override def updatePasswordHash(id: UUID, passwordHash: String): IO[Unit] =
    sql"UPDATE accounts SET password_hash = $passwordHash WHERE id = $id".update.run.transact(xa).void

  /** Stamps `email_verified_at` only while it is still null, so re-verifying an account keeps the original time. */
  override def markVerified(id: UUID): IO[Unit] =
    sql"UPDATE accounts SET email_verified_at = now() WHERE id = $id AND email_verified_at IS NULL".update.run
      .transact(xa)
      .void
}
