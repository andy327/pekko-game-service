package com.andy327.server.http.auth

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class AuthValidationSpec extends AnyWordSpec with Matchers {

  private val valid = RegisterRequest("alice", "alice@example.com", "s3cretpw")

  "AuthValidation.validateRegister" should {
    "accept a well-formed request and trim username and email" in {
      AuthValidation.validateRegister(valid.copy(username = "  alice  ", email = "  alice@example.com  ")) shouldBe
        Right(RegisterRequest("alice", "alice@example.com", "s3cretpw"))
    }

    "not trim the password" in {
      val withSpaces = valid.copy(password = "  spaces  ")
      AuthValidation.validateRegister(withSpaces).map(_.password) shouldBe Right("  spaces  ")
    }

    "reject a blank username" in {
      AuthValidation.validateRegister(valid.copy(username = "   ")) shouldBe Left("Username must not be blank")
    }

    "reject an over-long username" in {
      AuthValidation.validateRegister(valid.copy(username = "a" * 101)).isLeft shouldBe true
    }

    "reject a blank email" in {
      AuthValidation.validateRegister(valid.copy(email = "   ")) shouldBe Left("Email must not be blank")
    }

    "reject a malformed email" in
      Seq("alice", "alice@", "@example.com", "alice@example", "a b@example.com").foreach { bad =>
        AuthValidation.validateRegister(valid.copy(email = bad)) shouldBe Left("Email is not a valid address")
      }

    "reject an over-long email" in {
      val longEmail = ("a" * 250) + "@b.co" // 255 chars, otherwise well-formed
      AuthValidation.validateRegister(valid.copy(email = longEmail)) shouldBe
        Left("Email must be at most 254 characters")
    }

    "reject a too-short password" in {
      AuthValidation.validateRegister(valid.copy(password = "short")) shouldBe
        Left("Password must be at least 8 characters")
    }

    "reject a too-long password" in {
      AuthValidation.validateRegister(valid.copy(password = "a" * 129)).isLeft shouldBe true
    }
  }

  "AuthValidation.validateLogin" should {
    "accept a well-formed request and trim the email" in {
      AuthValidation.validateLogin(LoginRequest("  alice@example.com  ", "s3cretpw")) shouldBe
        Right(LoginRequest("alice@example.com", "s3cretpw"))
    }

    "accept a short password (no length rule on login)" in {
      AuthValidation.validateLogin(LoginRequest("alice@example.com", "x")).isRight shouldBe true
    }

    "reject a blank email" in {
      AuthValidation.validateLogin(LoginRequest("   ", "s3cretpw")) shouldBe Left("Email must not be blank")
    }

    "reject a blank password" in {
      AuthValidation.validateLogin(LoginRequest("alice@example.com", "")) shouldBe Left("Password must not be blank")
    }
  }

  "AuthValidation.validatePasswordChange" should {
    "accept a present current password and a valid new password" in {
      AuthValidation.validatePasswordChange(ChangePasswordRequest("oldpw123", "newpw456")) shouldBe Right(())
    }

    "reject a blank current password" in {
      AuthValidation.validatePasswordChange(ChangePasswordRequest("", "newpw456")) shouldBe
        Left("Current password must not be blank")
    }

    "reject a too-short new password (same rule as registration)" in {
      AuthValidation.validatePasswordChange(ChangePasswordRequest("oldpw123", "short")) shouldBe
        Left("Password must be at least 8 characters")
    }

    "reject a too-long new password" in {
      AuthValidation.validatePasswordChange(ChangePasswordRequest("oldpw123", "a" * 129)).isLeft shouldBe true
    }
  }
}
