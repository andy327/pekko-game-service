package com.andy327.server.actors.persistence

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameId, GameType}

object PersistActor {

  import PersistenceProtocol._

  /** Internal wrapper for a finished load-operation. */
  final case class Loaded(replyTo: ActorRef[SnapshotLoaded], result: Either[Throwable, Option[Game[_, _, _, _, _]]])
      extends Command

  /** Internal wrapper for a finished save-operation. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends Command
}

/** Base trait for persistence actors that interact with GameManager to load and save games.
  *
  * Concrete implementations only need to supply the loading and saving behavior.
  */
trait PersistActor {

  import PersistenceProtocol._
  import PersistActor._

  /** Load a previously saved game snapshot. Returns None if no snapshot exists for the given ID. */
  def loadFromStore(gameId: GameId, gameType: GameType): IO[Option[Game[_, _, _, _, _]]]

  /** Persist the current game snapshot, overwriting any previously saved state for the same ID. */
  def saveToStore(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]

  final def behavior: Behavior[Command] =
    Behaviors.setup { context =>
      implicit val runtime: IORuntime = IORuntime.global

      Behaviors.receiveMessage {
        case LoadSnapshot(gameId, gameType, replyTo) =>
          context.pipeToSelf(loadFromStore(gameId, gameType).unsafeToFuture()) {
            case Success(value) => Loaded(replyTo, Right(value))
            case Failure(ex)    => Loaded(replyTo, Left(ex))
          }
          Behaviors.same

        case SaveSnapshot(gameId, gameType, game, replyTo) =>
          context.pipeToSelf(saveToStore(gameId, gameType, game).unsafeToFuture()) {
            case Success(value) => Saved(replyTo, Right(value))
            case Failure(ex)    => Saved(replyTo, Left(ex))
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
