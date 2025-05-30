package com.andy327.server.actors

import com.andy327.model.tictactoe._
import com.andy327.server.protocol.{Move, TicTacToeStatus}
import org.apache.pekko.actor.typed.{ActorRef, Behavior}
import org.apache.pekko.actor.typed.scaladsl.Behaviors

object TicTacToeActor {
  sealed trait Command
  case class MakeMove(playerId: String, loc: Location, replyTo: ActorRef[TicTacToeStatus]) extends Command
  case class GetStatus(replyTo: ActorRef[TicTacToeStatus]) extends Command

  def apply(player1: String, player2: String): Behavior[Command] =
    Behaviors.setup { _ =>
      val game = TicTacToe.empty
      active(game, player1, player2)
    }

  private def active(game: TicTacToe, player1: String, player2: String): Behavior[Command] = Behaviors.receive { (_, msg) =>
    msg match {
      case MakeMove(playerId, loc, replyTo) if game.status == InProgress &&
        ((game.currentPlayer == X && playerId == player1) || (game.currentPlayer == O && playerId == player2)) =>

        game.play(loc) match {
          case Right(nextState: TicTacToe) =>
            replyTo ! convertStatus(nextState)
            active(nextState, player1, player2)
          case Left(_) =>
            replyTo ! convertStatus(game)
            Behaviors.same
        }

      case GetStatus(replyTo) =>
        replyTo ! convertStatus(game)
        Behaviors.same

      case _ =>
        msg match {
          case MakeMove(_, _, replyTo) => replyTo ! convertStatus(game)
          case GetStatus(replyTo) => replyTo ! convertStatus(game)
        }
        Behaviors.same
    }
  }

  private def convertStatus(game: TicTacToe): TicTacToeStatus = {
    val boardStrings = game.board.map(_.map(_.map(_.toString).getOrElse("")))
    val winnerOpt = game.status match {
      case Won(mark) => Some(mark.toString)
      case _ => None
    }
    val draw = game.status == Draw
    val current = game.currentPlayer.toString
    TicTacToeStatus(boardStrings, current, winnerOpt, draw)
  }
}
