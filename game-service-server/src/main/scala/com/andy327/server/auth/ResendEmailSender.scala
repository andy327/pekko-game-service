package com.andy327.server.auth

import java.net.URI
import java.net.http.HttpResponse.BodyHandlers
import java.net.http.{HttpClient, HttpRequest}
import java.nio.charset.StandardCharsets

import cats.effect.IO

import io.circe.Json

/** An [[EmailSender]] that delivers via the Resend HTTP API (`POST https://api.resend.com/emails`).
  *
  * The raw token is embedded in a link back to the web client (`baseUrl`) — `/reset-password?token=…` for reset and
  * `/verify-email?token=…` for verification — so the recipient completes the flow in the browser. The API key is sent
  * as a bearer credential; a non-2xx response fails the `IO`.
  *
  * @param apiKey Resend API key
  * @param from the `From` address on sent mail
  * @param baseUrl origin the emailed links point back to
  */
// $COVERAGE-OFF$ external HTTP integration, exercised against a real provider rather than in unit tests
class ResendEmailSender(apiKey: String, from: String, baseUrl: String) extends EmailSender {
  import ResendEmailSender._

  private val client: HttpClient = HttpClient.newHttpClient()

  override def sendPasswordReset(to: String, token: String): IO[Unit] =
    send(
      to,
      subject = "Reset your password",
      html = s"""<p>Reset your password with the link below. It can be used once and expires soon.</p>
                |<p><a href="$baseUrl/reset-password?token=$token">Reset password</a></p>""".stripMargin
    )

  override def sendEmailVerification(to: String, token: String): IO[Unit] =
    send(
      to,
      subject = "Verify your email address",
      html = s"""<p>Confirm your email address with the link below.</p>
                |<p><a href="$baseUrl/verify-email?token=$token">Verify email</a></p>""".stripMargin
    )

  /** POSTs one message to Resend, failing the effect on any non-2xx response. */
  private def send(to: String, subject: String, html: String): IO[Unit] =
    IO.blocking {
      val body = Json
        .obj(
          "from" -> Json.fromString(from),
          "to" -> Json.arr(Json.fromString(to)),
          "subject" -> Json.fromString(subject),
          "html" -> Json.fromString(html)
        )
        .noSpaces

      val request = HttpRequest
        .newBuilder(URI.create(ApiUrl))
        .header("Authorization", s"Bearer $apiKey")
        .header("Content-Type", "application/json")
        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
        .build()

      client.send(request, BodyHandlers.ofString())
    }.flatMap { response =>
      if (response.statusCode() >= 200 && response.statusCode() < 300) IO.unit
      else IO.raiseError(new RuntimeException(s"Resend API returned ${response.statusCode()}: ${response.body()}"))
    }
}

object ResendEmailSender {
  private val ApiUrl = "https://api.resend.com/emails"
}
// $COVERAGE-ON$
