package com.andy327.server.http.routes

import java.util.UUID

import scala.util.Try

import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server._

import com.andy327.model.core.{GameId, GameType}

/**
 * Common HTTP route directives for parsing and validating parameters such as UUIDs and GameTypes.
 *
 * These helpers are designed for use in Pekko HTTP route definitions where path or query parameters need to be
 * converted into typed domain values. Each method returns a `Directive1` that either provides a validated value
 * or rejects the request with a 400 Bad Request response.
 *
 * Example use:
 * {{{
 *   pathPrefix(Segment) { gameIdStr =>
 *     parseGameId(gameIdStr) { gameId =>
 *       // gameId is safely typed as UUID
 *     }
 *   }
 * }}}
 */
object RouteDirectives {

  /**
   * Parses a game ID/UUID from a provided string, returning a Directive1 if valid. If invalid, responds with HTTP 400.
   */
  def parseGameId(idStr: String): Directive1[GameId] =
    Try(UUID.fromString(idStr)).toOption match {
      case Some(uuid) => provide(uuid)
      case None       => complete(StatusCodes.BadRequest -> s"Invalid UUID for game ID: $idStr")
    }

  /**
   * Parses a GameType from a provided string, returning a Directive1 if valid. If invalid, responds with HTTP 400.
   */
  def parseGameType(gameTypeStr: String): Directive1[GameType] =
    GameType.fromString(gameTypeStr) match {
      case Some(gameType) => provide(gameType)
      case None           => complete(StatusCodes.BadRequest -> s"Invalid game type: $gameTypeStr")
    }
}
