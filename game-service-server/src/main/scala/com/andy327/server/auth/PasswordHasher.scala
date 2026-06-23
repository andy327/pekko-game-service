package com.andy327.server.auth

import com.typesafe.config.{Config, ConfigFactory}

import com.password4j.types.Argon2
import com.password4j.{Argon2Function, Password}

/** Hashes and verifies passwords using Argon2id (memory-hard), via password4j.
  *
  * Hashes are PHC strings that carry their own cost parameters, so a hash always verifies under the parameters it was
  * made with, independent of current config. Raising the configured cost therefore doesn't invalidate existing hashes;
  * [[needsRehash]] flags the weaker ones so callers can upgrade them on the next successful login.
  *
  * @param memoryKib memory cost in KiB
  * @param iterations time cost (number of passes)
  * @param parallelism number of lanes
  * @param outputLength length of the derived hash in bytes
  * @param saltLength length of the randomly generated salt in bytes
  */
class PasswordHasher(
    memoryKib: Int,
    iterations: Int,
    parallelism: Int,
    outputLength: Int = 32,
    saltLength: Int = 16
) {
  private val argon2: Argon2Function =
    Argon2Function.getInstance(memoryKib, iterations, parallelism, outputLength, Argon2.ID)

  /** Hashes a plaintext password with a fresh random salt, returning the encoded PHC string. */
  def hash(plain: String): String =
    Password.hash(plain).addRandomSalt(saltLength).`with`(argon2).getResult

  /** Verifies a plaintext password against a stored PHC hash, using the parameters encoded in that hash (not the
    * currently configured ones) so that hashes produced under older parameters still verify.
    */
  def verify(plain: String, phc: String): Boolean =
    Password.check(plain, phc).`with`(Argon2Function.getInstanceFromHash(phc))

  /** True if `phc` encodes weaker cost parameters than the ones currently configured, i.e. it should be rehashed.
    * Unparseable hashes are left alone (returns false) rather than forcing a churn.
    */
  def needsRehash(phc: String): Boolean =
    PasswordHasher.parseParams(phc).exists { case (m, t, p) =>
      m != memoryKib || t != iterations || p != parallelism
    }
}

object PasswordHasher {

  /** Builds a hasher from the `auth.argon2` configuration block. */
  def fromConfig(config: Config = ConfigFactory.load()): PasswordHasher = {
    val argon2 = config.getConfig("auth.argon2")
    new PasswordHasher(
      memoryKib = argon2.getInt("memory-kib"),
      iterations = argon2.getInt("iterations"),
      parallelism = argon2.getInt("parallelism")
    )
  }

  /** Extracts the `(memory, iterations, parallelism)` cost parameters from an Argon2 PHC string, if it parses. */
  private val ParamPattern = """\$argon2(?:id|i|d)\$v=\d+\$m=(\d+),t=(\d+),p=(\d+)\$.*""".r
  private def parseParams(phc: String): Option[(Int, Int, Int)] = phc match {
    case ParamPattern(m, t, p) => Some((m.toInt, t.toInt, p.toInt))
    case _                     => None
  }
}
