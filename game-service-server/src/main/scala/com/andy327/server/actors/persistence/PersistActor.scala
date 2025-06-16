package com.andy327.server.actors.persistence

import scala.util.{Failure, Success, Try}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameType}

object PersistActor {

  import PersistenceProtocol._

  /** Internal wrapper for a finished load-operation. */
  final case class Loaded(replyTo: ActorRef[SnapshotLoaded], result: Either[Throwable, Option[Game[_, _, _, _, _]]])
      extends Command

  /** Internal wrapper for a finished save-operation. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends Command
}

/**
  * Base trait for persistence actors that interact with GameManager to load and save games.
  *
  * Concrete implementations only need to supply the loading and saving behavior.
  */
trait PersistActor {

  import PersistenceProtocol._
  import PersistActor._

  def loadFromStore(gameId: String, gameType: GameType): IO[Option[Game[_, _, _, _, _]]]
  def saveToStore(gameId: String, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]

  final def behavior: Behavior[Command] =
    Behaviors.setup { context =>
      implicit val runtime: IORuntime = IORuntime.global

      Behaviors.receiveMessage {
        case LoadSnapshot(gameId, gameType, replyTo) =>
          val result = Try(loadFromStore(gameId, gameType).unsafeToFuture())
          result match {
            case Success(fut) =>
              context.pipeToSelf(fut) {
                case Success(value) => Loaded(replyTo, Right(value))
                case Failure(ex)    => Loaded(replyTo, Left(ex))
              }
            case Failure(ex) => replyTo ! SnapshotLoaded(Left(ex))
          }
          Behaviors.same

        case SaveSnapshot(gameId, gameType, game, replyTo) =>
          val result = Try(saveToStore(gameId, gameType, game).unsafeToFuture())
          result match {
            case Success(fut) =>
              context.pipeToSelf(fut) {
                case Success(value) => Saved(replyTo, Right(value))
                case Failure(ex)    => Saved(replyTo, Left(ex))
              }
            case Failure(ex) => replyTo ! SnapshotSaved(Left(ex))
          }
          Behaviors.same

        case Loaded(replyTo, result) =>
          replyTo ! SnapshotLoaded(result)
          Behaviors.same

        case Saved(replyTo, result) =>
          replyTo ! SnapshotSaved(result)
          Behaviors.same
      }
    }
}
