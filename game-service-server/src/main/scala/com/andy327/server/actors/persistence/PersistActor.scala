package com.andy327.server.actors.persistence

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameType}

object PersistActor {

  import PersistenceProtocol._

  /** Internal wrapper for a finished load‑operation. */
  final case class Loaded(replyTo: ActorRef[SnapshotLoaded], result: Either[Throwable, Option[Game[_, _, _, _, _]]])
      extends Command

  /** Internal wrapper for a finished save‑operation. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends Command
}

/**
  * Trait that handles the persistence behavior to communicate with the GameManager.
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
          context.pipeToSelf(loadFromStore(gameId, gameType).attempt.unsafeToFuture()) {
            case Success(result) => Loaded(replyTo, result)
            case Failure(ex)     => Loaded(replyTo, Left(ex))
          }
          Behaviors.same

        case SaveSnapshot(gameId, gameType, game, replyTo) =>
          context.pipeToSelf(saveToStore(gameId, gameType, game).attempt.unsafeToFuture()) {
            case Success(_)  => Saved(replyTo, Right(()))
            case Failure(ex) => Saved(replyTo, Left(ex))
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
