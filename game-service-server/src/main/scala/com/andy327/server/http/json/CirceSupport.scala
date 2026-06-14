package com.andy327.server.http.json

import io.circe.parser.decode
import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.apache.pekko.http.scaladsl.marshalling.{Marshaller, ToEntityMarshaller}
import org.apache.pekko.http.scaladsl.model.{ContentTypes, HttpEntity, MediaTypes}
import org.apache.pekko.http.scaladsl.unmarshalling.{FromEntityUnmarshaller, Unmarshaller}

/** Bridges Circe codecs to Pekko HTTP marshalling, replacing pekko-http-spray-json.
  *
  * Any type with a Circe `Encoder` can be passed to `complete` via the generic [[circeEntityMarshaller]]. Decoding is
  * deliberately not generic: a wildcard-imported generic unmarshaller would shadow pekko's predefined
  * `FromEntityUnmarshaller[String]` (local imports outrank implicit scope) and break `responseAs[String]`. Instead,
  * [[circeUnmarshaller]] builds a per-type instance that [[JsonProtocol]] declares as an implicit `val` for each
  * request/response type that needs reading.
  *
  * A failed decode propagates the Circe error, which the `entity` directive surfaces as a
  * `MalformedRequestContentRejection` (HTTP 400), matching the previous spray-json behavior.
  */
trait CirceSupport {

  implicit def circeEntityMarshaller[A](implicit encoder: Encoder[A]): ToEntityMarshaller[A] =
    Marshaller.withFixedContentType(ContentTypes.`application/json`) { a =>
      // drop null fields so optional values (e.g. an absent winner) are omitted rather than serialized as null
      HttpEntity(ContentTypes.`application/json`, a.asJson.deepDropNullValues.noSpaces)
    }

  /** Builds a JSON entity unmarshaller for `A`; declared as a specific implicit `val` per type, not as a generic
    * implicit, to avoid shadowing the predefined `String` unmarshaller.
    */
  def circeUnmarshaller[A](implicit decoder: Decoder[A]): FromEntityUnmarshaller[A] =
    Unmarshaller.stringUnmarshaller
      .forContentTypes(MediaTypes.`application/json`)
      .map(data => decode[A](data).fold(throw _, identity))
}
