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

- 🔐 JWT-authenticated player actions
- 🏛️ Full lobby lifecycle — create, join, leave, list, and start matches
- 🎲 Multiple game types — Tic-Tac-Toe and Connect Four (Battleship in progress)
- ✅ Server-side move validation (turn order and legality enforced by the game model)
- ⚡ Real-time state delivery to all participants over WebSockets
- 🌫️ Per-viewer / fog-of-war state projection for hidden-information games and spectators
- 💬 In-match chat with persisted backscroll history
- 📜 Move history retrieval per game
- 💾 Durable game state with write-through caching and restart recovery

**Planned** (see [Roadmap](#roadmap))

- 📊 Metrics & analytics pipeline (event-driven, Prometheus)
- 📈 Horizontal scaling via Pekko Cluster Sharding
- 🔑 Real authentication (credentialed accounts, token expiry)
- 🕹️ Additional game types and an AI opponent

## Requirements

### Functional

- **Authentication** — players present a JWT to perform actions.
- **Lobbies** — create a lobby for a chosen game type; join, leave, and list open lobbies; start a match once the required number of players is present.
- **Gameplay** — submit moves; the server validates turn order and legality and applies them to authoritative game state.
- **Real-time updates** — connected players and spectators receive game-state changes pushed over WebSockets.
- **Hidden information** — players see only their own view of state; spectators receive a fog-of-war view (no leakage of hidden state).
- **Chat** — post messages within a match and retrieve recent history (backscroll).
- **History & status** — query a game's move history and current status.
- **Persistence & recovery** — game state is durably stored; in-progress games are restored after a restart.

### Non-functional

- **Correctness / consistency** — one actor per game is the single source of truth, serializing moves so concurrent requests cannot corrupt state.
- **Low latency** — state changes are pushed to clients in real time rather than polled.
- **Durability + performance** — PostgreSQL is the system of record; Redis provides a write-through hot cache and ephemeral stores.
- **Fault tolerance** — games recover from persisted snapshots on restart.
- **Security** — authenticated actions, and no hidden-state information leakage via per-viewer projection.
- **Extensibility** — new game types are added through the `Game` trait and a module registry, without touching the actor/HTTP plumbing.
- **Testability** — pure game logic is isolated from I/O; integration tests run against real Postgres and Redis.
- **Observability** — operational metrics/analytics (planned).
- **Scalability** — single-instance today; designed to scale horizontally via cluster sharding (see [Roadmap](#roadmap)).

## Architecture

<picture>
  <source media="(prefers-color-scheme: dark)" srcset="docs/images/architecture-dark.png">
  <img alt="High-level architecture diagram" src="docs/images/architecture.png" width="600">
</picture>

The system runs as a single service instance, fronted by a reverse proxy, backed by PostgreSQL and Redis:

- **Reverse Proxy / TLS** — terminates HTTPS, proxies HTTP + WebSocket traffic to the service, and serves as the public endpoint. (Becomes a true load balancer once the service scales horizontally.)
- **Game Service (Pekko ActorSystem)** — the application. A `GameManager` supervises a `LobbyManager`, a `PlayerManager` (one `PlayerActor` per connected client), one game actor per active match, and a persistence actor. The Pekko HTTP route layer handles REST + WebSocket endpoints and JWT validation.
- **PostgreSQL** — durable system of record: game snapshots and an append-only move log.
- **Redis** — write-through game-state cache, lobby store, and chat ring buffer.
- **Analytics (planned)** — game actors publish domain events to a `game-analytics:*` Redis pub/sub channel, consumed out-of-band by an analytics service exposing metrics to Prometheus.

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

<!-- TODO [P1]: prerequisites; `docker-compose up` for Postgres + Redis; required env vars (notably JWT_SECRET); `sbt run`; a 2-minute "create lobby → start → make a move" walkthrough. -->

_Coming soon._

## API reference

<!-- TODO [P2]: REST endpoints (auth, lobby, move, status, history, chat) as a table, plus the WebSocket protocol and event envelope shapes (GameStateUpdated, GameEnded, LobbyUpdated, ChatMessage). -->

_Coming soon._

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

- **Metrics & analytics** — an event-driven analytics consumer that subscribes to a `game-analytics:*` stream of domain events and exposes aggregate metrics (games played, move counts, durations, win/draw rates by game type) to Prometheus. The game actors already sit at the right emit points; this gives that event stream a first-class consumer.
- **Horizontal scaling (Pekko Cluster Sharding)** — today the service is single-instance (lobbies, game actors, and player sessions live in one JVM). The target is to shard game and lobby entities across a Pekko cluster so play is location-transparent across nodes. Cluster messaging would replace the current Redis event relay for cross-instance fan-out, while the analytics stream survives unchanged. Kept deliberately out of the main architecture diagram above so it reflects what's actually deployed.
- **Real authentication** — credentialed accounts and durable player identity (today players are JWT-only), token expiry, and removal of the dev token-issuance path. A prerequisite for per-player stats and leaderboards.
- **More game types** — finishing Battleship (hidden-state showcase), then additional turn-based games (e.g. Pig, Liar's Dice, Mastermind, Texas Hold 'Em).
- **AI opponent** — a bot player for single-player matches and testing.
- **Load testing** — throughput/latency benchmarking, plus retention policies for snapshots and move logs.

## License

This project is licensed under the [MIT License](LICENSE).
