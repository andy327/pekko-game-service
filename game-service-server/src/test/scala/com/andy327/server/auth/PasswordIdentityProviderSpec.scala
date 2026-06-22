package com.andy327.server.auth

import cats.effect.unsafe.implicits.global

import org.scalatest.OptionValues
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.persistence.db.InMemoryUserRepository

class PasswordIdentityProviderSpec extends AnyWordSpec with Matchers with OptionValues {

  // Small cost parameters keep tests fast; production reads real values from auth.argon2.
  private def hasher(memoryKib: Int = 256): PasswordHasher =
    new PasswordHasher(memoryKib, iterations = 1, parallelism = 1)

  private def provider(
      users: InMemoryUserRepository = new InMemoryUserRepository,
      h: PasswordHasher = hasher()
  ): PasswordIdentityProvider = new PasswordIdentityProvider(users, h)

  "PasswordIdentityProvider.register" should {
    "store a hashed (not plaintext) password and return the account" in {
      val users = new InMemoryUserRepository
      val account = provider(users).register("alice", "alice@example.com", "s3cret").unsafeRunSync()
        .getOrElse(fail("expected registration to succeed"))

      account.username shouldBe "alice"
      account.email shouldBe "alice@example.com"
      account.passwordHash.value should startWith("$argon2id$")
      account.passwordHash.value should not be "s3cret"
    }

    "reject a duplicate email with EmailAlreadyRegistered" in {
      val users = new InMemoryUserRepository
      val p = provider(users)
      p.register("alice", "alice@example.com", "pw1").unsafeRunSync()

      p.register("alice2", "ALICE@example.com", "pw2").unsafeRunSync() shouldBe Left(AuthError.EmailAlreadyRegistered)
    }
  }

  "PasswordIdentityProvider.authenticate" should {
    "succeed with correct credentials and return the account" in {
      val users = new InMemoryUserRepository
      val p = provider(users)
      val registered = p.register("alice", "alice@example.com", "s3cret").unsafeRunSync().toOption.get

      p.authenticate("alice@example.com", "s3cret").unsafeRunSync() shouldBe Right(registered)
    }

    "fail with InvalidCredentials when the password is wrong" in {
      val p = provider()
      p.register("alice", "alice@example.com", "s3cret").unsafeRunSync()

      p.authenticate("alice@example.com", "wrong").unsafeRunSync() shouldBe Left(AuthError.InvalidCredentials)
    }

    "fail with InvalidCredentials for an unknown email" in {
      provider().authenticate("nobody@example.com", "whatever").unsafeRunSync() shouldBe
        Left(AuthError.InvalidCredentials)
    }

    "fail with InvalidCredentials for an account that has no password" in {
      val users = new InMemoryUserRepository
      users.create("federated", "fed@example.com", None).unsafeRunSync()

      provider(users).authenticate("fed@example.com", "anything").unsafeRunSync() shouldBe
        Left(AuthError.InvalidCredentials)
    }

    "transparently upgrade the stored hash on login when parameters have been raised" in {
      val users = new InMemoryUserRepository
      // Register with weak parameters, then authenticate through a provider configured with stronger ones.
      provider(users, hasher(memoryKib = 256)).register("alice", "alice@example.com", "s3cret").unsafeRunSync()
      val weakHash = users.findByEmail("alice@example.com").unsafeRunSync().flatMap(_.passwordHash).value

      provider(users, hasher(memoryKib = 512)).authenticate("alice@example.com", "s3cret").unsafeRunSync() shouldBe a[
        Right[_, _]
      ]

      val upgraded = users.findByEmail("alice@example.com").unsafeRunSync().flatMap(_.passwordHash).value
      upgraded should not be weakHash
      upgraded should include("m=512")
    }
  }
}
