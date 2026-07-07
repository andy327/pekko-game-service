package com.andy327.server.http.routes

import org.apache.pekko.http.scaladsl.model.headers.{`Cache-Control`, CacheDirectives}
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route

/** Serves the static web UI bundled under `src/main/resources/web` on the classpath.
  *
  * The UI is plain HTML/CSS/JS with no build step, served same-origin with the API so the browser needs no CORS
  * handling and can talk to the REST and WebSocket endpoints directly. These routes must be composed last (after the API
  * routes) so the catch-all resource lookup only handles paths no API route claimed.
  *
  * Route Summary:
  *   - GET / - the application shell (`web/index.html`)
  *   - GET /{file} - any other asset under `web/` (e.g. `main.js`, the ES modules it imports, `style.css`)
  */
class StaticRoutes {

  /** Classpath resources carry a fixed, ancient `Last-Modified`, which browsers turn into a multi-year heuristic
    * freshness window — so an updated `main.js`/`index.html` is served from cache and never picked up. `no-cache` forces
    * the browser to revalidate every request; the resource directives still answer conditional GETs with a cheap `304`,
    * so this costs a round-trip but no payload when the asset is unchanged.
    */
  private val revalidate = `Cache-Control`(CacheDirectives.`no-cache`)

  val routes: Route =
    respondWithHeader(revalidate) {
      concat(
        pathSingleSlash {
          getFromResource("web/index.html")
        },
        getFromResourceDirectory("web")
      )
    }
}
