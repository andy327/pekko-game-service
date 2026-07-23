package com.andy327.actor.game.modules

import io.circe.Decoder
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.GameActor
import com.andy327.actor.game.{GameOperation, GameView, MovePayload}
import com.andy327.model.core.{Game, GameError, PlayerId}

/** Per-game-type plugin that bridges HTTP-layer concerns to the actor layer.
  *
  * Parameterized on `G` so that `project` is type-safe: the compiler guarantees that the concrete game model passed
  * to `project` matches the game type this module handles. The single cast lives in
  * [[com.andy327.actor.game.GameModuleBundle]] at the DB-deserialization boundary, not here.
  *
  * == Implementing a new game ==
  *
  * Create a singleton object extending `GameModule[G]` and implement the three members:
  *
  *  1. `moveDecoder` — a Circe `Decoder[MovePayload]` for the game's move payload subtype. The standard pattern is:
  *     {{{
  *       import cats.syntax.functor._
  *       import io.circe.generic.auto._
  *       override val moveDecoder: Decoder[MovePayload] = Decoder[MyGameMove].widen
  *     }}}
  *
  *  2. `toGameCommand` — map a [[com.andy327.actor.game.GameOperation]] to a
  *     [[com.andy327.actor.core.TurnBasedGameActor]] command. Handle three cases: the game's `MakeMove`
  *     payload (constructing the game's move type), a wrong payload type (return `Left(GameError.Unknown(...))`),
  *     and `GetState`:
  *     {{{
  *       override def toGameCommand(op: GameOperation, replyTo: ActorRef[Either[GameError, GameView]]) = op match {
  *         case GameOperation.MakeMove(playerId, MovePayload.MyGameMove(x, y)) =>
  *           Right(TurnBasedGameActor.MakeMove(playerId, MyMove(x, y), replyTo))
  *         case GameOperation.MakeMove(_, other) =>
  *           val name = Option(other).map(_.getClass.getSimpleName).getOrElse("null")
  *           Left(GameError.Unknown(s"Unsupported move type for MyGame: \$name"))
  *         case GameOperation.GetState =>
  *           Right(TurnBasedGameActor.GetState(replyTo))
  *       }
  *     }}}
  *
  *  3. `project` — build the game's view for `viewer`. Given a
  *     [[com.andy327.actor.game.GameProjection]] instance in the `GameProjection` companion, this is always the
  *     same one-liner:
  *     {{{
  *       override def project(game: MyGame, viewer: Option[PlayerId]): GameView =
  *         GameProjection.project(game, viewer)
  *     }}}
  *
  * @tparam G the concrete game model type this module handles
  */
trait GameModule[G <: Game[_, _, _, _, _]] {

  /** Circe decoder used by the HTTP move endpoint to deserialize the request body into a [[MovePayload]]. */
  def moveDecoder: Decoder[MovePayload]

  /** Convert a game-agnostic [[GameOperation]] into a game-specific actor command.
    *
    * @param op the operation to execute (MakeMove or GetState)
    * @param replyTo the actor that should receive the `Either[GameError, GameView]` result
    * @return `Right(command)` ready to send to the game actor, or `Left(error)` if the operation is invalid
    */
  def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameView]]
  ): Either[GameError, GameActor.GameCommand]

  /** Project a concrete game model into its [[GameView]] for `viewer`.
    *
    * @param game the game to project; guaranteed by [[com.andy327.actor.game.GameModuleBundle]] to be of type `G`
    * @param viewer `Some(playerId)` for that player's own view, or `None` for a public/spectator view
    * @return the corresponding `GameView`, ready for an in-process consumer or the JSON encoders
    */
  def project(game: G, viewer: Option[PlayerId]): GameView
}
