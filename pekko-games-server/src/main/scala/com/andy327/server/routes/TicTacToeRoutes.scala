package com.andy327.server.routes

import com.andy327.server.actors.{TicTacToeActor, GameManager}
import com.andy327.server.protocol.{TicTacToeStatus, Move}
import com.andy327.server.protocol.JsonProtocol._
import com.andy327.model.tictactoe.Location

import org.apache.pekko.actor.typed.{ActorSystem, Scheduler}
import org.apache.pekko.actor.typed.scaladsl.AskPattern._
import org.apache.pekko.http.scaladsl.server.Directives._
import org.apache.pekko.http.scaladsl.server.Route
import org.apache.pekko.util.Timeout
import spray.json._
import org.apache.pekko.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext

class TicTacToeRoutes(system: ActorSystem[GameManager.Command])(implicit ec: ExecutionContext) {
  implicit val timeout: Timeout = 3.seconds
  implicit val scheduler: Scheduler = system.scheduler

  val routes: Route = pathPrefix("game") {
    pathEndOrSingleSlash {
      post {
        parameters("player1", "player2") { (p1, p2) =>
          onSuccess(system.ask(GameManager.CreateTicTacToe(p1, p2, _))) { gameId =>
            complete(gameId)
          }
        }
      }
    } ~
    path(Segment / "move") { gameId =>
      post {
        entity(as[Move]) { case Move(playerId, row, col) =>
          onSuccess(system.ask[TicTacToeStatus](ref => GameManager.RouteCommand(gameId, TicTacToeActor.MakeMove(playerId, Location(row, col), ref)))) { status =>
            complete(status)
          }
        }
      }
    } ~
    path(Segment / "status") { gameId =>
      get {
        onSuccess(system.ask[TicTacToeStatus](ref => GameManager.RouteCommand(gameId, TicTacToeActor.GetStatus(ref)))) { status =>
          complete(status)
        }
      }
    }
  }
}
