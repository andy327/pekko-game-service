package com.andy327.server.auth

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class PasswordHasherSpec extends AnyWordSpec with Matchers {

  // Small cost parameters keep tests fast; production reads real values from auth.argon2.
  private def hasher(memoryKib: Int = 256, iterations: Int = 1, parallelism: Int = 1): PasswordHasher =
    new PasswordHasher(memoryKib, iterations, parallelism)

  "PasswordHasher" should {
    "produce an argon2id PHC hash that verifies against the original password" in {
      val h = hasher()
      val phc = h.hash("correct horse battery staple")

      phc should startWith("$argon2id$")
      h.verify("correct horse battery staple", phc) shouldBe true
    }

    "reject an incorrect password" in {
      val h = hasher()
      val phc = h.hash("s3cret")

      h.verify("wrong", phc) shouldBe false
    }

    "produce distinct hashes for the same password (random salt) that both verify" in {
      val h = hasher()
      val phc1 = h.hash("same")
      val phc2 = h.hash("same")

      phc1 should not be phc2
      h.verify("same", phc1) shouldBe true
      h.verify("same", phc2) shouldBe true
    }

    "report needsRehash only when configured parameters differ from the stored hash" in {
      val weak = hasher(memoryKib = 256)
      val strong = hasher(memoryKib = 512)
      val phc = weak.hash("pw")

      weak.needsRehash(phc) shouldBe false // produced with the same parameters
      strong.needsRehash(phc) shouldBe true // configured cost has since been raised
    }

    "leave an unparseable hash alone rather than forcing a rehash" in {
      hasher().needsRehash("not-a-phc-string") shouldBe false
    }

    // Exercises the real application.conf: the auth.argon2 path/keys must exist and hold valid Argon2 parameters.
    "build a working hasher from the auth.argon2 configuration" in {
      val h = PasswordHasher.fromConfig()
      val phc = h.hash("pw")

      phc should startWith("$argon2id$")
      h.verify("pw", phc) shouldBe true
    }
  }
}
