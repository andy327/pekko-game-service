package com.andy327.server.lobby

import org.slf4j.LoggerFactory

import cats.effect.IO
import cats.implicits._

import dev.profunktor.redis4cats.RedisCommands

import com.andy327.model.core.GameId

/** A [[LobbyRepository]] backed by Redis.
  *
  * Each lobby is stored as a JSON string at key `lobby:{gameId}`. A Redis Set at `lobbies:active` tracks which game
  * IDs are currently active, allowing [[loadAllLobbies]] to enumerate all lobbies without a keyspace scan.
  *
  * Terminal lobbies (Completed, Cancelled) are removed from both the individual key and the active set via
  * [[deleteLobby]], so only active and in-progress lobbies survive a server restart.
  *
  * @param redis Redis commands handle used for all cache reads and writes
  */
class RedisLobbyRepository(redis: RedisCommands[IO, String, String]) extends LobbyRepository {

  private val logger = LoggerFactory.getLogger(getClass)
  private val ActiveSetKey = "lobbies:active"

  private def lobbyKey(gameId: GameId): String = s"lobby:$gameId"

  override def saveLobby(metadata: LobbyMetadata): IO[Unit] =
    redis.set(lobbyKey(metadata.gameId), LobbyCodecs.serialize(metadata)) *>
      redis.sAdd(ActiveSetKey, metadata.gameId.toString).void

  override def deleteLobby(gameId: GameId): IO[Unit] =
    redis.del(lobbyKey(gameId)).void *>
      redis.sRem(ActiveSetKey, gameId.toString).void

  override def loadAllLobbies(): IO[List[LobbyMetadata]] =
    redis.sMembers(ActiveSetKey).flatMap { ids =>
      ids.toList.flatTraverse { idStr =>
        redis.get(s"lobby:$idStr").flatMap {
          case Some(json) =>
            IO.fromEither(LobbyCodecs.deserialize(json))
              .map(List(_))
              .handleErrorWith { err =>
                IO(logger.warn(s"Skipping corrupt lobby $idStr: ${err.getMessage}")).as(Nil)
              }
          case None =>
            IO(logger.warn(s"Lobby $idStr found in active set but missing from Redis; skipping")).as(Nil)
        }
      }
    }
}
