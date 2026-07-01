package com.andy327.server.http.routes

import scala.concurrent.duration._

import io.circe.syntax._
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.actor.typed.{ActorRef, ActorSystem}
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.model.ws.{Message, TextMessage}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.stream.scaladsl.{Flow, Sink}
import org.apache.pekko.stream.typed.scaladsl.ActorSource
import org.apache.pekko.stream.{Materializer, OverflowStrategy}
import org.apache.pekko.util.Timeout

import com.andy327.actor.core.GameManager
import com.andy327.actor.tracing.{TraceCollector, TraceEvent}
import com.andy327.server.http.auth.JwtPlayerDirectives._
import com.andy327.server.http.json.JsonProtocol._

/** Debug HTTP route that streams live `TraceEvent`s for actor message tracing visualization.
  *
  * On connect the client must supply a valid JWT, exactly like [[WebSocketRoutes]] (Bearer header, or `access_token`
  * query parameter for browser clients). After authenticating, GameManager is asked for the live `trace-collector`
  * ref; if tracing is disabled (`None`), the request is rejected with 503 rather than upgrading to a socket that
  * would never receive anything. Otherwise the connection upgrades and the existing buffer plus every subsequent
  * `TraceEvent` is streamed to the client as JSON text frames, oldest first. The socket is push-only: inbound client
  * frames are accepted but ignored, since the debug viewer has no client-to-server protocol.
  *
  * Route Summary:
  *   - GET /ws/trace - Stream live TraceEvents (Auth: Bearer token, or `access_token` query parameter)
  *
  * Actor relationships:
  *   - Sends to: `GameManager` (`GetTraceCollector` on connect), the resolved `trace-collector`
  *     (`TraceCollector.Subscribe` on connect, `TraceCollector.Unsubscribe` on close)
  */
class TraceRoutes(gameManager: ActorSystem[GameManager.Command]) {
  implicit private val system: ActorSystem[GameManager.Command] = gameManager
  implicit private val timeout: Timeout = Timeout(5.seconds)
  implicit private val mat: Materializer = Materializer(system)
  private val ec = system.executionContext

  val routes: Route = path("ws" / "trace") {
    authenticatePlayerAllowingQueryParam { _ =>
      onSuccess(gameManager ? GameManager.GetTraceCollector) {
        case Some(collector) => handleWebSocketMessages(buildFlow(collector))
        case None            => complete(StatusCodes.ServiceUnavailable -> "tracing is disabled")
      }
    }
  }

  /** Render a TraceEvent as a JSON text frame for delivery to the client. */
  private def render(event: TraceEvent): String = event.asJson.noSpaces

  /** Builds a push-only WebSocket Flow that streams `TraceEvent`s from `collector`.
    *
    * Pre-materializes an ActorSource over `TraceEvent` to obtain a ref before the stream starts, then subscribes it
    * to `collector` (which immediately replays its buffer, then forwards every later event). Inbound frames are
    * accepted and discarded. On stream termination (WebSocket closed by either side), the source ref is unsubscribed
    * from `collector`.
    */
  private def buildFlow(collector: ActorRef[TraceCollector.Command]): Flow[Message, Message, Any] = {
    val (traceOut, source) = ActorSource
      .actorRef[TraceEvent](
        completionMatcher = PartialFunction.empty,
        failureMatcher = PartialFunction.empty,
        bufferSize = 256,
        overflowStrategy = OverflowStrategy.dropHead
      )
      .preMaterialize()

    collector ! TraceCollector.Subscribe(traceOut)

    val inbound: Sink[Message, Any] = Sink.ignore

    val outbound = source
      .map(event => TextMessage(render(event)))
      .watchTermination() { (_, done) =>
        done.onComplete(_ => collector ! TraceCollector.Unsubscribe(traceOut))(ec)
      }

    Flow.fromSinkAndSource(inbound, outbound)
  }
}
