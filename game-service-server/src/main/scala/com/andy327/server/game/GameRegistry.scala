package com.andy327.server.game

import com.andy327.model.core.{Game, GameType, PlayerId}
import com.andy327.server.actors.battleship.BattleshipActor
import com.andy327.server.actors.connectfour.ConnectFourActor
import com.andy327.server.actors.core.GameActor
import com.andy327.server.actors.tictactoe.TicTacToeActor
import com.andy327.server.game.modules.{BattleshipModule, ConnectFourModule, GameModule, TicTacToeModule}

/** Groups the [[com.andy327.server.game.modules.GameModule]] and [[com.andy327.server.actors.core.GameActor]] implementations for a single game type.
  *
  * The type parameter `G` ties module and actor together so that `module.serialize` and `actor.create`/`fromSnapshot`
  * are guaranteed to work with the same concrete game model. The `serializeGame` method is the single place where the
  * existential `Game[_, _, _, _, _]` coming from the database is cast to `G`.
  *
  * @param module the HTTP-layer plugin (move decoding, command mapping, serialization)
  * @param actor the actor-layer plugin (game creation, snapshot recovery, subscribe)
  * @tparam G the concrete game model type shared by both plugins
  */
case class GameModuleBundle[G <: Game[_, _, _, _, _]](module: GameModule[G], actor: GameActor[G]) {

  /** Serialize `game` to its HTTP representation, rendered for `viewer` (`None` = public/spectator view).
    *
    * The caller must ensure that `game` was loaded using this bundle's `GameType`; the cast is safe because
    * [[GameRegistry]] constructs each bundle with matching `G` types.
    */
  def serializeGame(game: Game[_, _, _, _, _], viewer: Option[PlayerId]): GameState =
    module.serialize(game.asInstanceOf[G], viewer)
}

/** Maps each supported `GameType` to its [[GameModuleBundle]].
  *
  * Adding a new game type requires registering a new case here and providing a `GameModule` and `GameActor`
  * implementation.
  */
object GameRegistry {

  /** Look up the bundle for a given `GameType`.
    *
    * @param gameType the game type to look up
    * @return the bundle containing module and actor for that game type
    */
  def forType(gameType: GameType): GameModuleBundle[_] = gameType match {
    case GameType.TicTacToe   => GameModuleBundle(TicTacToeModule, TicTacToeActor)
    case GameType.ConnectFour => GameModuleBundle(ConnectFourModule, ConnectFourActor)
    case GameType.Battleship  => GameModuleBundle(BattleshipModule, BattleshipActor)
  }
}
