package com.andy327.actor.game.modules

import cats.syntax.functor._

import io.circe.Decoder
import io.circe.generic.auto._
import org.apache.pekko.actor.typed.ActorRef

import com.andy327.actor.core.{GameActor, TurnBasedGameActor}
import com.andy327.actor.game.MovePayload.BattleshipMove
import com.andy327.actor.game.{GameOperation, GameState, GameStateConverters, MovePayload}
import com.andy327.model.battleship.{Battleship, Coord, Fire}
import com.andy327.model.core.{GameError, PlayerId}

/** [[GameModule]] implementation for Battleship.
  *
  * Provides move decoding, operation-to-command mapping, and per-viewer serialization for Battleship. Enables
  * [[core.GameManager]] and the HTTP routes to handle Battleship games without any game-specific logic.
  */
object BattleshipModule extends GameModule[Battleship] {

  override val moveDecoder: Decoder[MovePayload] = Decoder[BattleshipMove].widen

  override def toGameCommand(
      op: GameOperation,
      replyTo: ActorRef[Either[GameError, GameState]]
  ): Either[GameError, GameActor.GameCommand] = op match {
    case GameOperation.MakeMove(playerId, MovePayload.BattleshipMove(row, col)) =>
      Right(TurnBasedGameActor.MakeMove(playerId, Fire(Coord(row, col)), replyTo))

    case GameOperation.MakeMove(_, otherMove) =>
      val name = Option(otherMove).map(_.getClass.getSimpleName).getOrElse("null")
      Left(GameError.Unknown(s"Unsupported move type for Battleship: $name"))

    case GameOperation.GetState =>
      Right(TurnBasedGameActor.GetState(replyTo))
  }

  override def serialize(game: Battleship, viewer: Option[PlayerId]): GameState =
    GameStateConverters.serializeGame(game, viewer)
}
