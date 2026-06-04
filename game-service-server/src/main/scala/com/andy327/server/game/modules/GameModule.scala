package com.andy327.server.game.modules

import io.circe.Decoder
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.model.core.{Game, GameError}
import com.andy327.server.actors.core.GameActor
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.GameState

/** Per-game-type plugin that bridges HTTP-layer concerns to the actor layer.
  *
  * Parameterized on `G` so that `serialize` is type-safe: the compiler guarantees that the concrete game model passed
  * to `serialize` matches the game type this module handles. The single cast lives in
  * [[com.andy327.server.game.GameModuleBundle]] at the DB-deserialization boundary, not here.
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
  *  2. `toGameCommand` — map a [[com.andy327.server.game.GameOperation]] to a game-specific actor command.
  *     Handle three cases: the game's `MakeMove` payload (constructing the actor command), a wrong payload type
  *     (return `Left(GameError.Unknown(...))`), and `GetState` (delegate to the actor's `GetState` command):
  *     {{{
  *       override def toGameCommand(op: GameOperation, replyTo: ActorRef[Either[GameError, GameState]]) = op match {
  *         case GameOperation.MakeMove(playerId, MovePayload.MyGameMove(x, y)) =>
  *           Right(MyGameActor.MakeMove(playerId, MyMove(x, y), replyTo))
  *         case GameOperation.MakeMove(_, other) =>
  *           val name = Option(other).map(_.getClass.getSimpleName).getOrElse("null")
  *           Left(GameError.Unknown(s"Unsupported move type for MyGame: \$name"))
  *         case GameOperation.GetState =>
  *           Right(MyGameActor.GetState(replyTo))
  *       }
  *     }}}
  *
  *  3. `serialize` — convert the game model to its HTTP view. If a [[com.andy327.server.http.json.GameStateView]]
  *     instance is defined in the companion object of the view type, this is always the same one-liner:
  *     {{{
  *       override def serialize(game: MyGame): GameState = GameStateConverters.serializeGame(game)
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
    * @param replyTo the actor that should receive the `Either[GameError, GameState]` result
    * @return `Right(command)` ready to send to the game actor, or `Left(error)` if the operation is invalid
    */
  def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand]

  /** Convert a concrete game model into its HTTP-serializable `GameState` representation.
    *
    * @param game the game to serialize; guaranteed by [[com.andy327.server.game.GameModuleBundle]] to be of type `G`
    * @return the corresponding `GameState` for delivery over HTTP or WebSocket
    */
  def serialize(game: G): GameState
}
