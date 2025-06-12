package com.andy327.server.http.json

/**
 * A move made by a player in a Tic-Tac-Toe game.
 */
case class TicTacToeMove(playerId: String, row: Int, col: Int)
