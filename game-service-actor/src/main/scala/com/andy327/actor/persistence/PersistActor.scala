package com.andy327.actor.persistence

import scala.util.{Failure, Success}

import cats.effect.IO
import cats.effect.unsafe.IORuntime

import io.circe.Json
import org.apache.pekko.actor.typed.scaladsl.Behaviors
import org.apache.pekko.actor.typed.{ActorRef, Behavior}

import com.andy327.model.core.{Game, GameType, MatchId, PlayerId}
import com.andy327.persistence.db.PlayerHistoryRepository.GameResult

object PersistActor {

  import PersistenceProtocol._

  /** Internal wrapper for a finished save-operation. */
  final case class Saved(replyTo: ActorRef[SnapshotSaved], result: Either[Throwable, Unit]) extends Command

  /** Internal wrapper for a finished move-append; logged but never replied to (append is fire-and-forget). */
  final case class MoveAppended(matchId: MatchId, seq: Int, result: Either[Throwable, Unit]) extends Command

  /** Internal wrapper for a finished game-result record; logged but never replied to (recording is fire-and-forget). */
  final case class GameResultRecorded(matchId: MatchId, playerId: PlayerId, result: Either[Throwable, Unit])
      extends Command
}

/** Base trait for persistence actors that save game snapshots, append move-history records, and record per-player game
  * results on behalf of game actors.
  *
  * Concrete implementations supply the snapshot-save, move-append, and result-record behavior.
  */
trait PersistActor {

  import PersistenceProtocol._
  import PersistActor._

  /** Persist the current game snapshot, overwriting any previously saved state for the same ID. */
  def saveToStore(matchId: MatchId, gameType: GameType, game: Game[_, _, _, _, _]): IO[Unit]

  /** Append a single move to the game's history log. */
  def appendMoveToStore(matchId: MatchId, seq: Int, playerId: PlayerId, move: Json): IO[Unit]

  /** Record one participant's outcome for a completed game in their durable history. */
  def recordGameResultToStore(
      playerId: PlayerId,
      matchId: MatchId,
      gameType: GameType,
      result: GameResult,
      forfeit: Boolean
  ): IO[Unit]

  final def behavior(implicit runtime: IORuntime): Behavior[Command] =
    Behaviors.setup { context =>
      Behaviors.receiveMessage {
        case SaveSnapshot(matchId, gameType, game, replyTo) =>
          context.pipeToSelf(saveToStore(matchId, gameType, game).unsafeToFuture()) {
            case Success(value) => Saved(replyTo, Right(value))
            case Failure(ex)    => Saved(replyTo, Left(ex))
          }
          Behaviors.same

        case AppendMove(matchId, seq, playerId, move) =>
          context.pipeToSelf(appendMoveToStore(matchId, seq, playerId, move).unsafeToFuture()) {
            case Success(value) => MoveAppended(matchId, seq, Right(value))
            case Failure(ex)    => MoveAppended(matchId, seq, Left(ex))
          }
          Behaviors.same

        case Saved(replyTo, result) =>
          replyTo ! SnapshotSaved(result)
          Behaviors.same

        case MoveAppended(matchId, seq, result) =>
          result.left.foreach(ex =>
            context.log.warn(s"Move append failed for game $matchId seq $seq: ${ex.getMessage}")
          )
          Behaviors.same

        case RecordGameResult(playerId, matchId, gameType, result, forfeit) =>
          context.pipeToSelf(recordGameResultToStore(playerId, matchId, gameType, result, forfeit).unsafeToFuture()) {
            case Success(value) => GameResultRecorded(matchId, playerId, Right(value))
            case Failure(ex)    => GameResultRecorded(matchId, playerId, Left(ex))
          }
          Behaviors.same

        case GameResultRecorded(matchId, playerId, result) =>
          result.left.foreach(ex =>
            context.log.warn(s"Game-result record failed for game $matchId player $playerId: ${ex.getMessage}")
          )
          Behaviors.same
      }
    }
}
