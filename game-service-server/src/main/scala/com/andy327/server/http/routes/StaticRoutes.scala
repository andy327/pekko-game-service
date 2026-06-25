package com.andy327.server.http.routes

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
  *   - GET /{file} - any other asset under `web/` (e.g. `app.js`, `style.css`)
  */
class StaticRoutes {

  val routes: Route =
    concat(
      pathSingleSlash {
        getFromResource("web/index.html")
      },
      getFromResourceDirectory("web")
    )
}
