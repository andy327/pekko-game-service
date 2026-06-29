package com.andy327.persistence.db.redis

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.implicits._

import dev.profunktor.redis4cats.RedisCommands

import com.andy327.model.core.{Game, GameType, MatchId}
import com.andy327.persistence.db.GameRepository
import com.andy327.persistence.db.schema.GameTypeCodecs

/** A write-through caching [[GameRepository]] that stores game snapshots in Redis and delegates persistence to an
  * underlying Postgres repository.
  *
  * On every [[saveGame]], the game state is persisted to Postgres first, then written to Redis. A Redis failure after a
  * successful Postgres write leaves the cache cold but consistent; the next [[loadGame]] will repopulate it. On
  * [[loadGame]], the Redis cache is checked first; on a miss the game is fetched from Postgres and the cache is warmed.
  * On startup, [[loadAllGames]] reads the authoritative state from Postgres and populates Redis in bulk.
  *
  * Cache keys use the scheme `game:{matchId}`. Values are the JSON strings Postgres also stores in `game_state`.
  *
  * @param gameRepo underlying [[GameRepository]] used as the source of truth for all reads and writes; in production
  *                 this is the Postgres repository, but any [[GameRepository]] implementation may be supplied
  * @param redis Redis commands handle used for cache reads and writes
  */
class RedisGameRepository(
    gameRepo: GameRepository,
    redis: RedisCommands[IO, String, String]
) extends GameRepository {

  private val logger = LoggerFactory.getLogger(getClass)

  private def cacheKey(matchId: MatchId): String = s"game:$matchId"

  override def initialize(): IO[Unit] = gameRepo.initialize()

  override def saveGame(matchId: MatchId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit] = {
    val json = GameTypeCodecs.serializeGame(gameType, game)
    gameRepo.saveGame(matchId, gameType, game) *> redis.set(cacheKey(matchId), json)
  }

  override def loadGame(matchId: MatchId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] =
    redis.get(cacheKey(matchId)).flatMap {
      case Some(json) =>
        IO.fromEither(GameTypeCodecs.deserializeGame(gameType, json))
          .map(Some(_))
          .handleErrorWith { err =>
            IO(logger.warn(s"Corrupt cache entry for $matchId, falling back to Postgres: ${err.getMessage}")) *>
              loadFromPostgresAndWarm(matchId, gameType)
          }
      case None =>
        loadFromPostgresAndWarm(matchId, gameType)
    }

  override def loadAllGames(): IO[Map[MatchId, (GameType, Game[_, _, _, _, _])]] =
    gameRepo.loadAllGames().flatTap { games =>
      games.toList.traverse_ { case (matchId, (gameType, game)) =>
        redis.set(cacheKey(matchId), GameTypeCodecs.serializeGame(gameType, game))
      }
    }

  private def loadFromPostgresAndWarm(matchId: MatchId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]] =
    gameRepo.loadGame(matchId, gameType).flatTap {
      case Some(game) => redis.set(cacheKey(matchId), GameTypeCodecs.serializeGame(gameType, game))
      case None       => IO.unit
    }
}
