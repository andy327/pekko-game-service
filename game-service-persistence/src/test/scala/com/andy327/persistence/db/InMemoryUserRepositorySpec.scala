package com.andy327.persistence.db

import java.util.UUID

import cats.effect.unsafe.implicits.global

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.persistence.db.UserRepository.CreateError

class InMemoryUserRepositorySpec extends AnyWordSpec with Matchers {

  private val repo = new InMemoryUserRepository

  "InMemoryUserRepository" should {
    "treat initialize as a no-op, leaving the repository usable" in {
      repo.initialize().unsafeRunSync() // no schema to create; must complete without effect

      val account = repo.create("ivan", "ivan@example.com", Some("hash-i")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))
      repo.findById(account.id).unsafeRunSync() shouldBe Some(account)
    }

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

    "create accounts unverified" in {
      val account = repo.create("fred", "fred@example.com", Some("hash-f")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))
      account.emailVerifiedAt shouldBe None
    }

    "mark an account verified, stamping a verification time" in {
      val account = repo.create("gina", "gina@example.com", Some("hash-g")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))

      repo.markVerified(account.id).unsafeRunSync()

      repo.findById(account.id).unsafeRunSync().flatMap(_.emailVerifiedAt) shouldBe defined
    }

    "keep the original timestamp when an already-verified account is re-verified (idempotent)" in {
      val account = repo.create("hank", "hank@example.com", Some("hash-h")).unsafeRunSync()
        .getOrElse(fail("expected account creation to succeed"))

      repo.markVerified(account.id).unsafeRunSync()
      val first = repo.findById(account.id).unsafeRunSync().flatMap(_.emailVerifiedAt)
      repo.markVerified(account.id).unsafeRunSync()
      val second = repo.findById(account.id).unsafeRunSync().flatMap(_.emailVerifiedAt)

      first shouldBe defined
      second shouldBe first
    }

    "treat marking an unknown account verified as a no-op" in
      repo.markVerified(UUID.randomUUID()).unsafeRunSync() // must not throw or create a row

    "return None for an unknown email or id" in {
      repo.findByEmail("nobody@example.com").unsafeRunSync() shouldBe None
      repo.findById(UUID.randomUUID()).unsafeRunSync() shouldBe None
    }
  }
}
