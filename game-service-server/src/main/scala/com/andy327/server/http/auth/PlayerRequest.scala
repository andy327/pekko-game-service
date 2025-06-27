package com.andy327.server.http.auth

/**
 * Represents an incoming request to generate a JWT token for a player.
 *
 * @param id Optional UUID string representing the player's ID. If omitted, the server will generate a new UUID.
 * @param name The display name of the player
 */
case class PlayerRequest(id: Option[String], name: String)
