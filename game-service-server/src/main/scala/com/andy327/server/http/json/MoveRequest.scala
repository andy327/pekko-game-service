package com.andy327.server.http.json

/**
 * Represents the JSON payload submitted by a client to make a move in a Tic-Tac-Toe game.
 *
 * This is used exclusively for HTTP request deserialization, and is decoupled from internal game logic.
 * It is converted to a domain-level MovePayload (e.g., `MovePayload.TicTacToeMove`) before being processed.
 */
case class TicTacToeMoveRequest(row: Int, col: Int)
