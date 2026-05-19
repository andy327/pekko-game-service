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

  /** Convert a concrete game model into its HTTP-serializable [[com.andy327.server.http.json.GameState]] representation.
    *
    * @param game the game to serialize; guaranteed by [[com.andy327.server.game.GameModuleBundle]] to be of type `G`
    * @return the corresponding [[GameState]] for delivery over HTTP or WebSocket
    */
  def serialize(game: G): GameState
}
