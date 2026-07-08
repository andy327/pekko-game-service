package com.andy327.server.http.auth

/** Validation and normalization for auth request bodies, applied before any credential work.
  *
  * Keeps obviously-bad input (blank fields, malformed email, too-short/long password) out of the identity provider, and
  * trims surrounding whitespace from the identifying fields so " a@b.co " and "a@b.co" are the same account.
  */
object AuthValidation {

  /** Maximum accepted lengths; the password range also bounds hashing work per request. */
  private val MaxUsernameLength = 100
  private val MaxEmailLength = 254 // RFC 5321 max length of an email address
  private val MinPasswordLength = 8
  private val MaxPasswordLength = 128

  /** Deliberately permissive: a single `@` with non-empty, dot-bearing host, no whitespace. */
  private val EmailPattern = """^[^@\s]+@[^@\s]+\.[^@\s]+$""".r

  /** Validates and normalizes a registration request, returning the cleaned request or the first error.
    *
    * The username and email are trimmed; the password is checked as-is (surrounding spaces may be intentional).
    */
  def validateRegister(req: RegisterRequest): Either[String, RegisterRequest] = {
    val username = req.username.trim
    val email = req.email.trim

    if (username.isEmpty) Left("Username must not be blank")
    else if (username.length > MaxUsernameLength) Left(s"Username must be at most $MaxUsernameLength characters")
    else if (email.isEmpty) Left("Email must not be blank")
    else if (email.length > MaxEmailLength) Left(s"Email must be at most $MaxEmailLength characters")
    else if (!EmailPattern.matches(email)) Left("Email is not a valid address")
    else passwordError(req.password).toLeft(RegisterRequest(username, email, req.password))
  }

  /** Validates and normalizes a login request: only blankness is checked, so it can never reject a credential that
    * registration accepted, and rejecting blanks early avoids the (deliberately slow) verification work. The email is
    * trimmed so it matches the trimmed form stored at registration; the password is checked as-is.
    */
  def validateLogin(req: LoginRequest): Either[String, LoginRequest] = {
    val email = req.email.trim

    if (email.isEmpty) Left("Email must not be blank")
    else if (req.password.isEmpty) Left("Password must not be blank")
    else Right(LoginRequest(email, req.password))
  }

  /** Validates a password-change request: the current password must be present, and the new password must satisfy the
    * same length rules as registration.
    */
  def validatePasswordChange(req: ChangePasswordRequest): Either[String, Unit] =
    if (req.currentPassword.isEmpty) Left("Current password must not be blank")
    else passwordError(req.newPassword).toLeft(())

  /** Normalizes a forgot-password request by trimming the email. It never rejects: the endpoint answers 202 regardless,
    * so surfacing a validation error here would reveal whether an address is even well-formed.
    */
  def normalizeForgotPassword(req: ForgotPasswordRequest): ForgotPasswordRequest =
    ForgotPasswordRequest(req.email.trim)

  /** Validates a reset-password request: the token must be present, and the new password must satisfy the same length
    * rules as registration.
    */
  def validateResetPassword(req: ResetPasswordRequest): Either[String, Unit] =
    if (req.token.isEmpty) Left("Token must not be blank")
    else passwordError(req.newPassword).toLeft(())

  /** Validates an email-verification request: the token must be present. */
  def validateVerifyEmail(req: VerifyEmailRequest): Either[String, Unit] =
    if (req.token.isEmpty) Left("Token must not be blank") else Right(())

  /** Normalizes a resend-verification request by trimming the email. Like [[normalizeForgotPassword]] it never rejects,
    * since the endpoint answers 202 regardless and must not reveal whether an address is well-formed or registered.
    */
  def normalizeResendVerification(req: ResendVerificationRequest): ResendVerificationRequest =
    ResendVerificationRequest(req.email.trim)

  /** The length-rule violation for a candidate password, if any — shared by registration and password change. */
  private def passwordError(password: String): Option[String] =
    if (password.length < MinPasswordLength) Some(s"Password must be at least $MinPasswordLength characters")
    else if (password.length > MaxPasswordLength) Some(s"Password must be at most $MaxPasswordLength characters")
    else None
}
