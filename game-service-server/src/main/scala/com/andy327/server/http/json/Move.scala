package com.andy327.server.http.json

import com.andy327.model.core.PlayerId

/**
 * A move made by a player in a Tic-Tac-Toe game.
 */
case class TicTacToeMove(player: PlayerId, row: Int, col: Int)
