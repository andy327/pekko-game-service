package com.andy327.server.actors.persistence

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameId, GameType, PlayerId}

object PersistActor {

  import PersistenceProtocol._

  /** Internal wrapper for a finished save-operation. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends Command

  /** Internal wrapper for a finished move-append; logged but never replied to (append is fire-and-forget). */
  final case class MoveAppended(gameId: GameId, seq: Int, result: Either[Throwable, Unit]) extends Command
}

/** Base trait for persistence actors that save game snapshots and append move-history records on behalf of game actors.
  *
  * Concrete implementations supply the snapshot-save and move-append behavior.
  */
trait PersistActor {

  import PersistenceProtocol._
  import PersistActor._

  /** Persist the current game snapshot, overwriting any previously saved state for the same ID. */
  def saveToStore(gameId: GameId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]

  /** Append a single move to the game's history log. */
  def appendMoveToStore(gameId: GameId, seq: Int, playerId: PlayerId, move: Json): IO[Unit]

  final def behavior(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case SaveSnapshot(gameId, gameType, game, replyTo) =>
          context.pipeToSelf(saveToStore(gameId, gameType, game).unsafeToFuture()) {
            case Success(value) => Saved(replyTo, Right(value))
            case Failure(ex)    => Saved(replyTo, Left(ex))
          }
          Behaviors.same

        case AppendMove(gameId, seq, playerId, move) =>
          context.pipeToSelf(appendMoveToStore(gameId, seq, playerId, move).unsafeToFuture()) {
            case Success(value) => MoveAppended(gameId, seq, Right(value))
            case Failure(ex)    => MoveAppended(gameId, seq, Left(ex))
          }
          Behaviors.same

        case Saved(replyTo, result) =>
          replyTo ! SnapshotSaved(result)
          Behaviors.same

        case MoveAppended(gameId, seq, result) =>
          result.left.foreach(ex => context.log.warn(s"Move append failed for game $gameId seq $seq: ${ex.getMessage}"))
          Behaviors.same
      }
    }
}
