package com.andy327.server.actors.persistence

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameId, GameType}

object PersistActor {

  import PersistenceProtocol._

  /** Internal wrapper for a finished save-operation. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends Command
}

/** Base trait for persistence actors that save game snapshots on behalf of game actors.
  *
  * Concrete implementations only need to supply the saving behavior.
  */
trait PersistActor {

  import PersistenceProtocol._
  import PersistActor._

  /** Persist the current game snapshot, overwriting any previously saved state for the same ID. */
  def saveToStore(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]

  final def behavior(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case SaveSnapshot(gameId, gameType, game, replyTo) =>
          context.pipeToSelf(saveToStore(gameId, gameType, game).unsafeToFuture()) {
            case Success(value) => Saved(replyTo, Right(value))
            case Failure(ex)    => Saved(replyTo, Left(ex))
          }
          Behaviors.same

        case Saved(replyTo, result) =>
          replyTo ! SnapshotSaved(result)
          Behaviors.same
      }
    }
}
