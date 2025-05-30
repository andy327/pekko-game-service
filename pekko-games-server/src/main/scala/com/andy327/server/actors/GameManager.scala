package com.andy327.server.actors

import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object GameManager {
  sealed trait Command
  case class CreateTicTacToe(player1: String, player2: String, replyTo: ActorRef[String]) extends Command
  case class RouteCommand(gameId: String, cmd: TicTacToeActor.Command) extends Command

  def apply(): Behavior[Command] = Behaviors.setup { ctx =>
    var games = Map.empty[String, ActorRef[TicTacToeActor.Command]]

    Behaviors.receiveMessage {
      case CreateTicTacToe(p1, p2, replyTo) =>
        val id = java.util.UUID.randomUUID().toString
        val gameActor = ctx.spawn(TicTacToeActor(p1, p2), s"tictactoe-$id")
        games += id -> gameActor
        replyTo ! id
        Behaviors.same

      case RouteCommand(gameId, cmd) =>
        games.get(gameId).foreach(_ ! cmd)
        Behaviors.same
    }
  }
}