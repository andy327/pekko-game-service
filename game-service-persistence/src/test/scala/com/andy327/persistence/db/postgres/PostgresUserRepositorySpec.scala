package com.andy327.persistence.db.postgres

import java.util.UUID

import cats.effect.IO
import cats.effect.unsafe.implicits.global

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import doobie.Transactor
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.persistence.db.UserRepository.CreateError

class PostgresUserRepositorySpec extends AnyWordSpec with Matchers with ForAllTestContainer {

  /** Single PostgreSQL container spun up once per ScalaTest run. */
  override val container: PostgreSQLContainer = PostgreSQLContainer()

  private lazy val xa: Transactor[IO] = Transactor.fromDriverManager[IO](
    driver = "org.postgresql.Driver",
    url = container.jdbcUrl,
    user = container.username,
    password = container.password,
    None
  )

  private lazy val repo = new PostgresUserRepository(xa)

  override def afterStart(): Unit =
    repo.initialize().unsafeRunSync()

  "PostgresUserRepository" should {
    "create an account and find it by email and by id" in {
      val account = repo.create("alice", "alice@example.com", Some("hash-a")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))

      account.username shouldBe "alice"
      account.email shouldBe "alice@example.com"
      account.passwordHash shouldBe Some("hash-a")
      account.createdAt should not be null

      repo.findByEmail("alice@example.com").unsafeRunSync() shouldBe Some(account)
      repo.findById(account.id).unsafeRunSync() shouldBe Some(account)
    }

    "match email case-insensitively" in {
      repo.create("bob", "Bob@Example.com", Some("hash-b")).unsafeRunSync()

      repo.findByEmail("bob@example.com").unsafeRunSync().map(_.username) shouldBe Some("bob")
      repo.findByEmail("BOB@EXAMPLE.COM").unsafeRunSync().map(_.username) shouldBe Some("bob")
    }

    "reject a duplicate email (case-insensitively) with EmailAlreadyExists" in {
      repo.create("carol", "carol@example.com", Some("hash-c")).unsafeRunSync()

      repo.create("carol2", "CAROL@example.com", Some("hash-c2")).unsafeRunSync() shouldBe
        Left(CreateError.EmailAlreadyExists)
    }

    "allow an account with no password hash (passwordless/federated)" in {
      val created = repo.create("dave", "dave@example.com", None).unsafeRunSync()
      created.toOption.flatMap(_.passwordHash) shouldBe None
    }

    "update an account's password hash" in {
      val account = repo.create("erin", "erin@example.com", Some("old")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))

      repo.updatePasswordHash(account.id, "new").unsafeRunSync()

      repo.findById(account.id).unsafeRunSync().flatMap(_.passwordHash) shouldBe Some("new")
    }

    "create accounts unverified and mark them verified idempotently" in {
      val account = repo.create("fred", "fred@example.com", Some("hash-f")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))
      account.emailVerifiedAt shouldBe None

      repo.markVerified(account.id).unsafeRunSync()
      val first = repo.findById(account.id).unsafeRunSync().flatMap(_.emailVerifiedAt)
      first shouldBe defined

      // Re-verifying keeps the original timestamp rather than moving it forward.
      repo.markVerified(account.id).unsafeRunSync()
      repo.findById(account.id).unsafeRunSync().flatMap(_.emailVerifiedAt) shouldBe first
    }

    "treat marking an unknown account verified as a no-op" in
      repo.markVerified(UUID.randomUUID()).unsafeRunSync() // affects no rows

    "run initialize idempotently, so the additive migration is safe on every startup" in {
      repo.initialize().unsafeRunSync()
      repo.initialize().unsafeRunSync() // CREATE ... IF NOT EXISTS / ADD COLUMN IF NOT EXISTS must not fail on re-run

      val account = repo.create("ida", "ida@example.com", Some("hash-i")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))
      repo.findById(account.id).unsafeRunSync().map(_.username) shouldBe Some("ida")
    }

    "return None for an unknown email or id" in {
      repo.findByEmail("nobody@example.com").unsafeRunSync() shouldBe None
      repo.findById(UUID.randomUUID()).unsafeRunSync() shouldBe None
    }
  }
}
