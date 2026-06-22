package com.andy327.persistence.db

import java.time.Instant
import java.util.UUID

import cats.effect.IO
import cats.effect.kernel.Ref

import com.andy327.persistence.db.UserRepository.CreateError

/** A [[UserRepository]] that keeps accounts in memory, with the same semantics as the Postgres implementation
  * (case-insensitive email uniqueness, atomic create).
  *
  * Unlike the NoOp repositories, this one actually stores accounts so credential round-trips work — it is the default
  * where Postgres-backed accounts are not wired (tests, local runs); the real [[postgres.PostgresUserRepository]] is
  * supplied in production.
  */
class InMemoryUserRepository extends UserRepository {

  private val accounts: Ref[IO, Map[UUID, Account]] = Ref.unsafe(Map.empty)

  override def initialize(): IO[Unit] = IO.unit

  override def findById(id: UUID): IO[Option[Account]] =
    accounts.get.map(_.get(id))

  override def findByEmail(email: String): IO[Option[Account]] =
    accounts.get.map(_.values.find(_.email.equalsIgnoreCase(email)))

  override def create(
      username: String,
      email: String,
      passwordHash: Option[String]
  ): IO[Either[CreateError, Account]] =
    accounts.modify { current =>
      if (current.values.exists(_.email.equalsIgnoreCase(email)))
        (current, Left(CreateError.EmailAlreadyExists))
      else {
        val account = Account(UUID.randomUUID(), username, email, passwordHash, Instant.now())
        (current + (account.id -> account), Right(account))
      }
    }

  override def updatePasswordHash(id: UUID, passwordHash: String): IO[Unit] =
    accounts.update(current =>
      current.get(id).fold(current)(account => current + (id -> account.copy(passwordHash = Some(passwordHash))))
    )
}
