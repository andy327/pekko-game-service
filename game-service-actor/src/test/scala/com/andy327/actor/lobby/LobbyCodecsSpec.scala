package com.andy327.actor.lobby

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.bot.BotDifficulty
import com.andy327.model.core.GameType

class LobbyCodecsSpec extends AnyWordSpec with Matchers {

  private val baseLobby = LobbyMetadata.newLobby(GameType.TicTacToe, Player("alice"))

  "LobbyCodecs.serialize / deserialize" should {
    "round-trip a lobby with WaitingForPlayers status" in {
      val lobby = baseLobby.copy(status = GameLifecycleStatus.WaitingForPlayers)
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "round-trip a lobby with ReadyToStart status" in {
      val lobby = baseLobby.copy(status = GameLifecycleStatus.ReadyToStart)
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "round-trip a lobby with InProgress status" in {
      val lobby = baseLobby.copy(status = GameLifecycleStatus.InProgress)
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "round-trip a lobby with Finished status" in {
      val lobby = baseLobby.copy(status = GameLifecycleStatus.Finished)
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "round-trip a lobby with Completed status" in {
      val lobby = baseLobby.copy(status = GameLifecycleStatus.Completed)
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "round-trip a lobby with Cancelled status" in {
      val lobby = baseLobby.copy(status = GameLifecycleStatus.Cancelled)
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "round-trip a lobby with a name" in {
      val lobby = baseLobby.copy(name = Some("Friday night"))
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "default name to None when decoding legacy JSON that predates the field" in {
      val legacyJson = LobbyCodecs.serialize(baseLobby).replaceAll(""",?"name":null""", "")
      LobbyCodecs.deserialize(legacyJson) shouldBe Right(baseLobby.copy(name = None))
    }

    "round-trip a lobby carrying bot seats and their difficulties" in {
      val bot = BotId.player(0)
      val lobby = baseLobby.copy(
        players = baseLobby.players + (bot.id -> bot),
        bots = Map(bot.id -> BotDifficulty.Standard)
      )
      LobbyCodecs.deserialize(LobbyCodecs.serialize(lobby)) shouldBe Right(lobby)
    }

    "default bots to empty when decoding a record written before bot seats existed" in {
      // circe's derived decoders ignore case-class defaults, so a room persisted by an older build would otherwise be
      // unreadable — costing it its name, host, and rematch on the first restart after an upgrade
      val legacyJson = LobbyCodecs.serialize(baseLobby).replaceAll(""",?"bots":\{\}""", "")
      (legacyJson should not).include("bots")
      LobbyCodecs.deserialize(legacyJson) shouldBe Right(baseLobby.copy(bots = Map.empty))
    }

    "return Left for a lobby JSON with an unknown difficulty" in {
      val bot = BotId.player(0)
      val lobby = baseLobby.copy(bots = Map(bot.id -> BotDifficulty.Standard))
      val json = LobbyCodecs.serialize(lobby).replace("\"standard\"", "\"impossible\"")
      LobbyCodecs.deserialize(json) shouldBe a[Left[_, _]]
    }

    "return Left for a lobby JSON containing an unknown status value" in {
      val json = LobbyCodecs.serialize(baseLobby).replace("WaitingForPlayers", "Exploded")
      LobbyCodecs.deserialize(json) shouldBe a[Left[_, _]]
    }

    "return Left for malformed JSON" in {
      LobbyCodecs.deserialize("not json") shouldBe a[Left[_, _]]
    }
  }
}
