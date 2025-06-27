package com.andy327.server.http.routes

import java.util.UUID

import scala.util.{Failure, Success, Try}

import io.circe.syntax._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import org.apache.pekko.http.scaladsl.model.StatusCodes
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import pdi.jwt.{JwtAlgorithm, JwtCirce}

import com.andy327.server.auth.UserContext
import com.andy327.server.config.JwtConfig
import com.andy327.server.http.auth.PlayerRequest
import com.andy327.server.http.json.JsonProtocol._
import com.andy327.server.lobby.Player

class AuthRoutes {
  val routes: Route = pathPrefix("auth") {
    path("token") {
      post {
        entity(as[PlayerRequest]) { req =>
          val maybePlayer: Either[String, Player] = req.id match {
            case Some(idStr) =>
              Try(UUID.fromString(idStr)) match {
                case Success(uuid) => Right(Player(uuid, req.name))
                case Failure(_)    => Left("Invalid UUID format in 'id' field")
              }
            case None =>
              Right(Player(req.name)) // generate random UUID
          }

          maybePlayer match {
            case Right(player) =>
              val user = UserContext(player.id.toString, player.name)
              val token = JwtCirce.encode(user.asJson, JwtConfig.secretKey, JwtAlgorithm.HS256)
              complete(Map("token" -> token))

            case Left(errorMessage) =>
              complete(StatusCodes.BadRequest -> Map("error" -> errorMessage))
          }
        }
      }
    }
  }
}
