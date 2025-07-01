# pekko-game-service

[![CI](https://github.com/andy327/pekko-game-service/actions/workflows/ci.yml/badge.svg)](https://github.com/andy327/pekko-game-service/actions/workflows/ci.yml)
[![codecov](https://codecov.io/gh/andy327/pekko-game-service/graph/badge.svg?token=UXQ6PPFF8T)](https://codecov.io/gh/andy327/pekko-game-service)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](https://opensource.org/licenses/MIT)

## Overview

**Pekko Game Service** is a multiplayer turn-based game backend built in **Scala**, leveraging modern libraries and actor-based concurrency for clean, modular game logic. It supports multiple game types, lobby management, and persistent storage.

### 🔧 Technologies Used

- **Scala 2.13** + **Pekko Typed Actors** for scalable, actor-based game orchestration
- **Pekko HTTP** for building RESTful APIs
- **Cats-Effect** for safe, composable side effects and integration with Doobie
- **Doobie** + **PostgreSQL** for persistent game state storage
- **Circe** for JSON (de)serialization of game state
- **Spray-Json** for API request/response formats
- **JWT authentication** for secure player actions
- **Scaffeine** for lightweight caching
- **Testcontainers** for integration testing with real databases
- **Modular game architecture** via a generic `GameRegistry` and game-specific modules

### 🚀 DevOps / CI/CD

- **GitHub Actions** for automated testing and code quality checks
- **Codecov** integration for test coverage reporting
- **Render.com** for continuous deployment of the HTTP API
- **Docker** for local development and deployment consistency

### 🎮 Features

- Full lobby lifecycle: create, join, start, and list lobbies
- Generic support for multiple game types (e.g., TicTacToe, more to come)
- Decoupled game logic and API layers for flexibility
- Ready for WebSocket and player actor extensions (planned)

_Documentation Coming Soon!_
