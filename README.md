# pekko-game-service

[![CI](https://github.com/andy327/pekko-game-service/actions/workflows/ci.yml/badge.svg)](https://github.com/andy327/pekko-game-service/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/andy327/pekko-game-service/graph/badge.svg?token=UXQ6PPFF8T)](https://codecov.io/gh/andy327/pekko-game-service)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

> A multiplayer, turn-based game backend in Scala/Pekko — real-time WebSocket play, durable game state, and a pluggable game-type model.

## Overview

**Pekko Game Service** is a backend for hosting multiplayer turn-based games. Players authenticate, gather in lobbies, and play matches whose moves are validated server-side and pushed to every participant in real time over WebSockets. Game state is the single source of truth held in a per-game actor, persisted to PostgreSQL, and cached in Redis so matches survive restarts.

The architecture is built around the actor model: each game is an isolated actor that serializes its own moves (no race conditions), and new game types plug in through a small `Game` trait plus a module registry. Games with hidden information (e.g. Battleship's fog of war) are supported through per-viewer state projection, so each player — and any spectator — only ever receives the slice of state they're allowed to see.

## Features

**Built**

- 🔐 Credentialed accounts — register, log in, and change password (Argon2-hashed), issuing expiring JWTs for player actions
- 🏛️ Full lobby lifecycle — create, join, leave, list, and start matches
- 🎲 Multiple game types — Tic-Tac-Toe, Connect Four, and Battleship
- ✅ Server-side move validation (turn order and legality enforced by the game model)
- ⚡ Real-time state delivery to all participants over WebSockets
- 🌫️ Per-viewer / fog-of-war state projection for hidden-information games and spectators
- 💬 In-match chat with persisted backscroll history
- 📜 Move history retrieval per game
- 🏅 Per-player game history — win/loss/draw records across completed matches, retrievable by the authenticated player
- 🧭 Live participation lookup — a player can ask "what am I in right now?" to re-discover their joined lobbies and active games after reconnecting
- 💾 Durable game state with write-through caching and restart recovery
- 📊 Metrics & analytics — game-lifecycle events aggregated into Prometheus metrics at a /metrics endpoint

**Planned** (see [Roadmap](#roadmap))

- 📈 Horizontal scaling via Pekko Cluster Sharding
- 🕹️ Additional game types and an AI opponent

## Requirements

### Functional

- **Authentication** — players register and log in with a password (Argon2-hashed) to obtain a JWT, which they present to perform actions. Tokens expire, so clients re-authenticate when a request returns 401; WebSocket connections are authenticated only at connect, so an expiring token never drops a live socket.
- **Lobbies** — create a lobby for a chosen game type; join, leave, and list open lobbies; start a match once the required number of players is present.
- **Gameplay** — submit moves; the server validates turn order and legality and applies them to authoritative game state.
- **Real-time updates** — connected players and spectators receive game-state changes pushed over WebSockets.
- **Hidden information** — players see only their own view of state; spectators receive a fog-of-war view (no leakage of hidden state).
- **Chat** — post messages within a match and retrieve recent history (backscroll).
- **History & status** — query a game's move history and current status, and retrieve the authenticated player's own record of completed games.
- **Live participation** — the authenticated player can list their current sessions: pre-game lobbies they've joined and in-progress games they're seated in (derived from live actor state, so it survives a reconnect or restart).
- **Persistence & recovery** — game state is durably stored; in-progress games are restored after a restart.

### Non-functional

- **Correctness / consistency** — one actor per game is the single source of truth, serializing moves so concurrent requests cannot corrupt state.
- **Low latency** — state changes are pushed to clients in real time rather than polled.
- **Durability + performance** — PostgreSQL is the system of record; Redis provides a write-through hot cache and ephemeral stores.
- **Fault tolerance** — games recover from persisted snapshots on restart.
- **Security** — authenticated actions, and no hidden-state information leakage via per-viewer projection.
- **Extensibility** — new game types are added through the `Game` trait and a module registry, without touching the actor/HTTP plumbing.
- **Testability** — pure game logic is isolated from I/O; integration tests run against real Postgres and Redis.
- **Observability** — game-lifecycle events are aggregated into Prometheus metrics, scrapable at `GET /metrics`.
- **Scalability** — single-instance today; designed to scale horizontally via cluster sharding (see [Roadmap](#roadmap)).

## Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/architecture-dark.png">
  <img alt="High-level architecture diagram" src="docs/images/architecture.png" width="600">
</picture>

The system runs as a single service instance, fronted by a reverse proxy, backed by PostgreSQL and Redis:

- **Reverse Proxy / TLS** — terminates HTTPS, proxies HTTP + WebSocket traffic to the service, and serves as the public endpoint. (Becomes a true load balancer once the service scales horizontally.)
- **Game Service (Pekko ActorSystem)** — the application. A `GameManager` supervises a `LobbyManager`, a `PlayerManager` (one `PlayerActor` per connected client), one game actor per active match, and a persistence actor. The Pekko HTTP route layer handles REST + WebSocket endpoints and JWT validation.
- **PostgreSQL** — durable system of record: game snapshots, an append-only move log, user accounts, and per-player game history.
- **Redis** — write-through game-state cache, lobby store, and chat ring buffer.
- **Analytics** — game actors publish domain events (game started, move made, game completed, chat sent) to a `game-analytics` Redis pub/sub channel; a decoupled consumer folds them into Prometheus metrics exposed at `GET /metrics`.

For the actor supervision tree, the move-flow sequence, and design rationale, see the [Design deep-dive](#design-deep-dive).

## Tech stack

| Area | Technologies |
|------|--------------|
| Language & runtime | Scala 2.13, Cats Effect, fs2 |
| Actors & HTTP | Pekko Typed Actors, Pekko HTTP, Pekko Streams |
| Persistence | Doobie, PostgreSQL, redis4cats (Lettuce), Redis |
| Serialization | Circe (JSON) |
| Auth | jwt-scala |
| Build & CI | sbt, GitHub Actions, Codecov |
| Infra & deploy | Docker / Docker Compose, Render |
| Testing | ScalaTest, Pekko TestKit, Testcontainers |

## Getting started

You'll need a JDK (17+), [sbt](https://www.scala-sbt.org/), and Docker (for Postgres and Redis). Clone the repo, bring up the datastores, and run the server:

```
$ git clone https://github.com/andy327/pekko-game-service.git
$ cd pekko-game-service/
$ docker-compose up -d          # starts PostgreSQL and Redis
$ sbt run
```

That's it — the service listens on `http://localhost:8080`. The schema is created on first boot, and the bundled defaults match the `docker-compose.yml` credentials, so there's nothing to configure for local play.

### Configuration

Every setting has a working default for local development and can be overridden by an environment variable:

| Variable | Default | Purpose |
|----------|---------|---------|
| `PORT` | `8080` | HTTP port the service binds to |
| `DATABASE_URL` | `jdbc:postgresql://localhost:5432/gamedb` | PostgreSQL connection string |
| `DB_USER` / `DB_PASSWORD` | `gameuser` / `gamepass` | PostgreSQL credentials |
| `REDIS_URI` | `redis://localhost:6379` | Redis connection string |
| `JWT_SECRET` | `local-dev-secret` | HMAC signing key for JWTs — **set this to a strong secret in any real deployment** |
| `JWT_TTL` | `1h` | Lifetime of an issued access token |
| `CHAT_MAX_MESSAGES` | `100` | Per-match chat backscroll retained for `GET /chat` |
| `ARGON2_MEMORY_KIB` / `ARGON2_ITERATIONS` / `ARGON2_PARALLELISM` | `19456` / `2` / `1` | Argon2id password-hashing cost (OWASP baseline) |

### A two-minute walkthrough

Tic-Tac-Toe needs two players, so we'll register two accounts, gather them in a lobby, start the match, and make the opening move. The examples use [HTTPie](https://httpie.io/) for readability; `curl` works just as well.

Register two players. Each call returns a signed JWT — grab one for each player:

```
$ http POST :8080/auth/register username=alice email=alice@example.com password=hunter2-aaaa
$ http POST :8080/auth/register username=bob   email=bob@example.com   password=hunter2-bbbb
```

```jsonc
{ "token": "eyJhbGciOi..." }   // save Alice's as $ALICE and Bob's as $BOB
```

Alice creates a Tic-Tac-Toe lobby (she becomes the host); the response carries the new `gameId`:

```
$ http POST :8080/lobby/create/tictactoe "Authorization: Bearer $ALICE"
```

Bob joins it, then Alice — the host — starts the match:

```
$ http POST :8080/lobby/$GAME_ID/join  "Authorization: Bearer $BOB"
$ http POST :8080/lobby/$GAME_ID/start "Authorization: Bearer $ALICE"
```

Now play. Moves are game-specific JSON; for Tic-Tac-Toe that's a zero-based `(row, col)`, with `(0,0)` at the top-left. Alice (X) takes the center:

```
$ http POST :8080/tictactoe/$GAME_ID/move "Authorization: Bearer $ALICE" row:=1 col:=1
```

The response is the updated board, and every connected participant is pushed the new state over their WebSocket in real time (see [API reference](#api-reference) for the `/ws` protocol). Bob can reply with his own `move`, and so on until someone wins or the board fills.

## API reference

All gameplay endpoints require a `Authorization: Bearer <jwt>` header; obtain a token from `/auth/register` or `/auth/token`. Paths with a `{gameType}` segment accept `tictactoe`, `connectfour`, or `battleship`.

### Auth

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/auth/register` | Create an account (`username`, `email`, `password`) and receive a JWT |
| `POST` | `/auth/token` | Authenticate with `email` + `password` and receive a JWT |
| `POST` | `/auth/password` | Change the authenticated account's password (does not revoke existing tokens) |
| `GET`  | `/auth/whoami` | Return the caller's player `id` and `name` decoded from the token |

### Lobbies

| Method | Path | Description |
|--------|------|-------------|
| `POST`   | `/lobby/create/{gameType}` | Create a lobby for a game type; the caller becomes host |
| `GET`    | `/lobby/list` | List all open lobbies |
| `GET`    | `/lobby/{gameId}` | Fetch metadata for one lobby |
| `POST`   | `/lobby/{gameId}/join` | Join an open lobby |
| `POST`   | `/lobby/{gameId}/leave` | Leave a lobby (or forfeit an in-progress game) |
| `POST`   | `/lobby/{gameId}/start` | Start the match (host only) |
| `DELETE` | `/lobby/{gameId}` | Cancel a pre-game lobby (host only) |
| `POST` / `DELETE` | `/lobby/{gameId}/subscribe` | Start / stop spectating a lobby's push events |

### Gameplay

| Method | Path | Description |
|--------|------|-------------|
| `POST`   | `/{gameType}/{gameId}/move` | Submit a move; payload shape is game-specific (e.g. `{ "row": 1, "col": 1 }` for Tic-Tac-Toe) |
| `GET`    | `/{gameType}/{gameId}/status` | Fetch current game state (your view) |
| `GET`    | `/{gameType}/{gameId}/history` | Fetch the ordered move log |
| `GET`    | `/{gameType}/{gameId}/chat` | Fetch recent chat backscroll |
| `POST` / `DELETE` | `/{gameType}/{gameId}/subscribe` | Start / stop spectating a game's push events |

### Player & metrics

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/players/me/sessions` | The caller's live lobbies and active games (survives reconnect) |
| `GET` | `/players/me/history` | The caller's completed-game win/loss/draw record |
| `GET` | `/metrics` | Prometheus metrics (text exposition format 0.0.4) |

### WebSocket protocol

After authenticating, a client opens a single WebSocket to `GET /ws` (Bearer token required at connect). The connection is the player's real-time channel for every match and lobby they're part of; it's authenticated only at connect, so an expiring token never drops a live socket.

**Server → client** frames are JSON-tagged by `type`:

| `type` | Payload | Sent when |
|--------|---------|-----------|
| `LobbyUpdated` | lobby `metadata` | A player joins/leaves or the lobby's status changes |
| `GameStateUpdated` | the viewer's `state` | A move is applied — each recipient gets their own per-viewer projection |
| `GameEnded` | final `result` | The game reaches a win or draw |
| `ChatMessage` | `gameId`, `senderId`, `senderName`, `text`, `sentAt` | Anyone watching that match posts chat |

**Client → server** frames are likewise tagged by `type`. Today the sole inbound message is chat; unrecognized or malformed frames are logged and dropped:

```jsonc
{ "type": "ChatSend", "gameId": "<uuid>", "text": "good luck!" }
```

REST actions (joining, starting, moving) and WebSocket pushes work together: you `POST` a move over HTTP and receive the resulting state both in the HTTP response and as a `GameStateUpdated` push to every participant.

## Design deep-dive

<!-- TODO [P2]: actor model & supervision (with actor diagram); move lifecycle (with sequence diagram); persistence strategy (snapshots vs move log, write-through cache, recovery); per-viewer / fog-of-war serialization; real-time delivery; how to add a new game type. -->

_Coming soon._

## Project structure

<!-- TODO: expand with a short description of each module's responsibilities. -->

- `game-service-model` — pure game logic (the `Game` trait and per-game implementations); no I/O.
- `game-service-persistence` — Doobie/PostgreSQL and Redis repositories, plus Circe codecs.
- `game-service-server` — Pekko actors, Pekko HTTP routes, JWT auth, and WebSocket delivery.

## Testing

<!-- TODO [P2]: what's covered (model specs, actor specs, integration tests against real Redis/Postgres containers) and how to run them. -->

_Coming soon._

## Roadmap

Planned work, in rough priority order:

- **Horizontal scaling (Pekko Cluster Sharding)** — today the service is single-instance (lobbies, game actors, and player sessions live in one JVM). The target is to shard game and lobby entities across a Pekko cluster so play is location-transparent across nodes. Cluster messaging would carry cross-instance delivery between game actors and player sessions directly, while the analytics event stream survives unchanged. Kept deliberately out of the main architecture diagram above so it reflects what's actually deployed.
- **Authentication hardening** — the credentialed auth in place covers registration, login, and password change, with Argon2id hashing, short-lived tokens, and login timing-equalization to blunt email enumeration. Considered and deliberately deferred: **token revocation** — JWTs are stateless, so a password change or "log out" doesn't invalidate already-issued tokens before they expire; closing that needs a per-account token version baked into the claim (or a `jti` denylist in Redis) checked at validation time. **Password reset** (forgot-password) — needs an out-of-band channel (email) and a single-use, TTL'd reset-token store (a natural fit for Redis), so it's gated on an email integration. **Rate limiting / lockout** on the auth endpoints to slow credential stuffing, and **email-address verification** at registration, are likewise out of scope for now. The short token TTL keeps the revocation gap small in the meantime.
- **OAuth / social login** — a second `IdentityProvider` (e.g. Google/GitHub) plus a callback route, resolving an external identity to the same `Account` and reusing token issuance and the account store unchanged. The `IdentityProvider` seam exists precisely so this is additive; the open design questions are account-linking policy (same email via password and OAuth) and how non-browser clients complete the redirect.
- **More game types** — additional turn-based games beyond the current three (e.g. Pig, Liar's Dice, Mastermind, Texas Hold 'Em).
- **AI opponent** — a bot player for single-player matches and testing.
- **Load testing** — throughput/latency benchmarking, plus retention policies for snapshots and move logs.

## License

This project is licensed under the [MIT License](LICENSE).
