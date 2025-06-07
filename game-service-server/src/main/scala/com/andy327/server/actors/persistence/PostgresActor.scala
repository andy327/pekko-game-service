package com.andy327.server.actors.persistence

import scala.util.{Failure, Success}

import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.Game
import com.andy327.persistence.db.GameRepository

/** Pekko‑typed wrapper over a GameRepository that persists snapshots. */
object PostgresActor {

  import PersistenceProtocol._

  sealed trait InternalCommand extends Command

  /** Result of LoadSnapshot, piped back into the actor. */
  final case class Loaded(replyTo: ActorRef[SnapshotLoaded], result: Either[Throwable, Option[Game[_, _, _, _, _]]])
      extends InternalCommand

  /** Result of SaveSnapshot, piped back into the actor. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends InternalCommand

  def apply(repository: GameRepository): Behavior[Command] =
    Behaviors.setup { context =>
      implicit val runtime: IORuntime = IORuntime.global

      Behaviors.receiveMessage {
        case LoadSnapshot(gameId, gameType, replyTo) =>
          context.pipeToSelf(repository.loadGame(gameId, gameType).attempt.unsafeToFuture()) {
            case Success(result) => Loaded(replyTo, result)
            case Failure(error)  => Loaded(replyTo, Left(error))
          }
          Behaviors.same

        case SaveSnapshot(gameId, gameType, game, replyTo) =>
          context.pipeToSelf(repository.saveGame(gameId, gameType, game).attempt.unsafeToFuture()) {
            case Success(result) => Saved(replyTo, result.map(_ => ()))
            case Failure(error)  => Saved(replyTo, Left(error))
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
