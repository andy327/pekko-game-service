package com.andy327.server.http.routes

import java.util.UUID

import io.prometheus.client.CollectorRegistry
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.http.scaladsl.testkit.ScalatestRouteTest
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.model.core.GameType
import com.andy327.server.analytics.{GameAnalyticsEvent, GameMetrics}

class MetricsRoutesSpec extends AnyWordSpec with Matchers with ScalatestRouteTest {

  "MetricsRoutes" should {
    "expose recorded metrics in the Prometheus text format at GET /metrics" in {
      val registry = new CollectorRegistry()
      val metrics = new GameMetrics(registry)
      metrics.record(GameAnalyticsEvent.GameStarted(UUID.randomUUID(), GameType.TicTacToe, 2))

      val routes: Route = new MetricsRoutes(registry).routes

      Get("/metrics") ~> routes ~> check {
        status shouldBe StatusCodes.OK
        contentType.mediaType.params.get("version") shouldBe Some("0.0.4")
        val body = responseAs[String]
        body should include("games_started_total")
        body should include("""games_started_total{game_type="tictactoe",} 1.0""")
      }
    }
  }
}
