package com.andy327.server.game.modules

import io.circe.Decoder
import org.apache.pekko.actor.typed.ActorRef
import spray.json.JsValue

import com.andy327.model.core.GameError
import com.andy327.server.actors.core.GameActor
import com.andy327.server.game.{GameOperation, MovePayload}
import com.andy327.server.http.json.GameState

/**
 * A trait that must be implemented for each supported game type.
 * It allows generic routing and game management logic to remain agnostic to the game being played.
 */
trait GameModule {

  def moveDecoder: Decoder[MovePayload]

  /**
   * Parses a MovePayload from raw JSON submitted by the client.
   * Used in generic game routes for POST /{gameId}/move.
   *
   * @param json JSON payload representing the move
   * @return Either a parsing error (String) or a MovePayload instance
   */
  def parseMove(json: JsValue): Either[String, MovePayload]

  /**
   * Converts a game-agnostic GameOperation into a game-specific actor command.
   * Used in GameManager when dispatching messages to a game actor.
   *
   * @param op The operation to run (MakeMove or GetState)
   * @param replyTo The actor to reply to with Either[GameError, GameState]
   * @return Either a game-specific actor command that can be sent to a GameActor or a GameError
   */
  def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand]
}
