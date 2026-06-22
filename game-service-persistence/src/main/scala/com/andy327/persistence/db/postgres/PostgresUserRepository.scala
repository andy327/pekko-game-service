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
  * Each account is one row in `accounts`, keyed by a server-generated UUID. Email uniqueness is enforced
  * case-insensitively by a functional unique index on `lower(email)`; a duplicate insert surfaces as
  * [[com.andy327.persistence.db.UserRepository.CreateError.EmailAlreadyExists]] rather than an exception, via the
  * unique-violation SQL state.
  */
class PostgresUserRepository(xa: Transactor[IO]) extends UserRepository {

  /** Tuple shape of an `accounts` row, mapped to [[Account]] without relying on case-class derivation. */
  private type Row = (UUID, String, String, Option[String], Instant)
  private def toAccount(row: Row): Account = Account(row._1, row._2, row._3, row._4, row._5)

  /** Creates the 'accounts' table and case-insensitive email uniqueness index if they don't already exist. */
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
        CREATE UNIQUE INDEX IF NOT EXISTS accounts_email_lower_idx ON accounts (lower(email))
      """.update.run
    } yield ()).transact(xa)

  override def findById(id: UUID): IO[Option[Account]] =
    sql"SELECT id, username, email, password_hash, created_at FROM accounts WHERE id = $id"
      .query[Row]
      .option
      .transact(xa)
      .map(_.map(toAccount))

  override def findByEmail(email: String): IO[Option[Account]] =
    sql"SELECT id, username, email, password_hash, created_at FROM accounts WHERE lower(email) = lower($email)"
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
      .map(_.map(createdAt => Account(id, username, email, passwordHash, createdAt)))
      .transact(xa)
  }

  override def updatePasswordHash(id: UUID, passwordHash: String): IO[Unit] =
    sql"UPDATE accounts SET password_hash = $passwordHash WHERE id = $id".update.run.transact(xa).void
}
