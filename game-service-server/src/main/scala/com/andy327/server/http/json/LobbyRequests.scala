package com.andy327.server.http.json

import com.andy327.server.lobby.Player

final case class CreateLobbyRequest(player: Player)
final case class JoinLobbyRequest(player: Player)
