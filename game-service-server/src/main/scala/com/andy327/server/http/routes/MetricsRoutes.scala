package com.andy327.server.http.routes

import java.io.StringWriter

import io.prometheus.client.CollectorRegistry
import io.prometheus.client.exporter.common.TextFormat
import org.apache.pekko.http.scaladsl.model.{ContentType, HttpCharsets, HttpEntity, MediaType}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

/** Exposes the Prometheus metrics registry for scraping.
  *
  * Route Summary:
  *   - GET /metrics — renders all registered collectors in the Prometheus text exposition format (0.0.4)
  *
  * @param registry the registry written to by [[com.andy327.server.analytics.GameMetrics]]
  */
class MetricsRoutes(registry: CollectorRegistry) {

  // Prometheus text exposition format 0.0.4: "text/plain; version=0.0.4; charset=UTF-8"
  private val mediaType: MediaType.WithFixedCharset =
    MediaType.customWithFixedCharset("text", "plain", HttpCharsets.`UTF-8`, params = Map("version" -> "0.0.4"))

  val routes: Route =
    path("metrics") {
      get {
        val writer = new StringWriter()
        TextFormat.write004(writer, registry.metricFamilySamples())
        complete(HttpEntity(ContentType.WithFixedCharset(mediaType), writer.toString))
      }
    }
}
