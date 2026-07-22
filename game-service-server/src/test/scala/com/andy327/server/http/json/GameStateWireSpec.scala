package com.andy327.server.http.json

import java.util.UUID

import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax._
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import com.andy327.actor.game.{GameState, GameStateConverters}
import com.andy327.model.battleship.{Battleship, Coord, Player1, Player2, PlayerBoard, Ship}
import com.andy327.model.checkers.{Black => CkBlack, Checkers, Piece, Red => CkRed}
import com.andy327.model.connectfour.{ConnectFour, Red => CfRed, Yellow => CfYellow}
import com.andy327.model.core.PlayerId
import com.andy327.model.holdem.{Card, Street, TexasHoldEm}
import com.andy327.model.liarsdice.{Bid, LiarsDice, StandingBid}
import com.andy327.model.mastermind.{Attempt, Feedback, Mastermind, Peg}
import com.andy327.model.pig.Pig
import com.andy327.model.tictactoe.{O, TicTacToe, X}

/** Pins the exact JSON every game view puts on the wire.
  *
  * Each case fixes a whole game model and compares the encoded result against a literal document, making this the
  * regression net for the projection layer: the view types and their encoders may be restructured freely, but if a
  * single byte the client sees changes, a case here fails. The models and the expected documents are therefore meant
  * to stay frozen while the code between them changes.
  *
  * Views are compared after `deepDropNullValues`, because that is what the transport applies before sending — see
  * `CirceSupport` for HTTP responses and `WebSocketRoutes.render` for pushed events. Asserting on the raw encoding
  * would pin absent fields that no client ever receives.
  */
class GameStateWireSpec extends AnyWordSpec with Matchers {
  import JsonProtocol._

  private val alice: PlayerId = UUID.randomUUID()
  private val bob: PlayerId = UUID.randomUUID()

  /** The document a client actually receives for `state`. */
  private def wire(state: GameState): Json = state.asJson.deepDropNullValues

  /** Parses an expected document, failing the test (rather than the suite) if the literal itself is malformed. */
  private def expected(raw: String): Json =
    parse(raw).fold(err => fail(s"malformed expected JSON: $err"), identity)

  "TicTacToe" should {
    "encode to its exact wire form" in {
      val game = TicTacToe(
        playerX = alice,
        playerO = bob,
        board = Vector(
          Vector(Some(X), None, None),
          Vector(None, Some(O), None),
          Vector(None, None, None)
        ),
        currentPlayer = X,
        winner = None,
        isDraw = false
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "board": [["X","",""],["","O",""],["","",""]],
          "currentPlayer": "X",
          "draw": false
        }
      """)
    }
  }

  "ConnectFour" should {
    "encode to its exact wire form, including a decided winner" in {
      val empty = Vector.fill(7)(Option.empty[com.andy327.model.connectfour.Mark])
      val game = ConnectFour(
        playerRed = alice,
        playerYellow = bob,
        board = Vector.fill(5)(empty) :+
          Vector(Some(CfRed), Some(CfYellow), None, None, None, None, None),
        currentPlayer = CfYellow,
        winner = Some(CfRed),
        isDraw = false
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "board": [
            ["","","","","","",""],
            ["","","","","","",""],
            ["","","","","","",""],
            ["","","","","","",""],
            ["","","","","","",""],
            ["R","Y","","","","",""]
          ],
          "currentPlayer": "Y",
          "winner": "R",
          "draw": false
        }
      """)
    }
  }

  "Checkers" should {
    "encode to its exact wire form, distinguishing pawns from kings by case" in {
      val blank = Vector.fill(8)(Option.empty[Piece])
      val game = Checkers(
        playerRed = alice,
        playerBlack = bob,
        board = Vector(
          blank,
          blank,
          blank.updated(3, Some(Piece(CkBlack, isKing = false))),
          blank,
          blank.updated(1, Some(Piece(CkRed, isKing = true))),
          blank.updated(0, Some(Piece(CkRed, isKing = false))),
          blank,
          blank
        ),
        currentPlayer = CkRed,
        winner = None,
        moveCount = 12
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "board": [
            ["","","","","","","",""],
            ["","","","","","","",""],
            ["","","","b","","","",""],
            ["","","","","","","",""],
            ["","R","","","","","",""],
            ["r","","","","","","",""],
            ["","","","","","","",""],
            ["","","","","","","",""]
          ],
          "currentPlayer": "R",
          "viewerSeat": "R"
        }
      """)
    }

    "encode a finished game to its exact wire form, naming the winning color" in {
      val blank = Vector.fill(8)(Option.empty[Piece])
      val game = Checkers(
        playerRed = alice,
        playerBlack = bob,
        board = Vector(
          blank,
          blank,
          blank,
          blank,
          blank,
          blank.updated(0, Some(Piece(CkRed, isKing = false))), // Black has no pieces left
          blank,
          blank
        ),
        currentPlayer = CkBlack,
        winner = Some(CkRed),
        moveCount = 47
      )

      wire(GameStateConverters.serializeGame(game, Some(bob))) shouldBe expected("""
        {
          "board": [
            ["","","","","","","",""],
            ["","","","","","","",""],
            ["","","","","","","",""],
            ["","","","","","","",""],
            ["","","","","","","",""],
            ["r","","","","","","",""],
            ["","","","","","","",""],
            ["","","","","","","",""]
          ],
          "currentPlayer": "B",
          "winner": "R",
          "viewerSeat": "B"
        }
      """)
    }
  }

  "Battleship" should {
    "encode to its exact wire form, revealing the viewer's fleet and fogging the opponent's" in {
      // P1 holds a 2-cell ship at (0,0)-(0,1) and has been fired at on (0,0) [hit] and (9,9) [miss];
      // P2 holds a 1-cell ship at (5,5), already hit.
      val game = Battleship(
        alice,
        bob,
        PlayerBoard(List(Ship(Set(Coord(0, 0), Coord(0, 1)))), Set(Coord(0, 0), Coord(9, 9))),
        PlayerBoard(List(Ship(Set(Coord(5, 5)))), Set(Coord(5, 5))),
        Player1,
        None
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "board1": [
            ["hit","ship","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","water"],
            ["water","water","water","water","water","water","water","water","water","miss"]
          ],
          "board2": [
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","hit","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"]
          ],
          "currentPlayer": "P1",
          "viewerSeat": "P1"
        }
      """)
    }

    "encode a finished game for a spectator to its exact wire form, naming the winner and fogging both fleets" in {
      // P2's single-cell fleet at (5,5) is fully sunk, so P1 has won; P1's fleet took one hit at (0,0)
      val game = Battleship(
        alice,
        bob,
        PlayerBoard(List(Ship(Set(Coord(0, 0), Coord(0, 1)))), Set(Coord(0, 0))),
        PlayerBoard(List(Ship(Set(Coord(5, 5)))), Set(Coord(5, 5))),
        Player2,
        Some(Player1)
      )

      wire(GameStateConverters.serializeGame(game, None)) shouldBe expected("""
        {
          "board1": [
            ["hit","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"]
          ],
          "board2": [
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","hit","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"],
            ["unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown","unknown"]
          ],
          "currentPlayer": "P2",
          "winner": "P1"
        }
      """)
    }
  }

  "Pig" should {
    "encode to its exact wire form" in {
      val game = Pig(
        playerIds = Vector(alice, bob),
        scores = Vector(23, 41),
        currentSeat = 1,
        turnScore = 7,
        lastRoll = Some(4),
        winner = None,
        moveCount = 9
      )

      wire(GameStateConverters.serializeGame(game, Some(bob))) shouldBe expected("""
        {
          "scores": {"P1": 23, "P2": 41},
          "currentPlayer": "P2",
          "turnScore": 7,
          "lastRoll": 4,
          "viewerSeat": "P2"
        }
      """)
    }

    "encode a finished game to its exact wire form, naming the winning seat" in {
      val game = Pig(
        playerIds = Vector(alice, bob),
        scores = Vector(45, 100),
        currentSeat = 1,
        turnScore = 0,
        lastRoll = None,
        winner = Some(1),
        moveCount = 31
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "scores": {"P1": 45, "P2": 100},
          "currentPlayer": "P2",
          "turnScore": 0,
          "winner": "P2",
          "viewerSeat": "P1"
        }
      """)
    }
  }

  "Mastermind" should {
    "encode to its exact wire form, withholding the secret from the codebreaker" in {
      val game = Mastermind(
        codemakerId = alice,
        codebreakerId = bob,
        secret = Some(Vector(Peg.Red, Peg.Blue, Peg.Green, Peg.Yellow)),
        guesses = List(Attempt(Vector(Peg.Red, Peg.Red, Peg.Blue, Peg.Blue), Feedback(black = 1, white = 1))),
        winner = None
      )

      wire(GameStateConverters.serializeGame(game, Some(bob))) shouldBe expected("""
        {
          "guesses": [{"pegs": ["red","red","blue","blue"], "black": 1, "white": 1}],
          "currentPlayer": "codebreaker",
          "guessesRemaining": 9,
          "viewerRole": "codebreaker"
        }
      """)
    }

    "encode the secret to its exact wire form for the codemaker" in {
      val game = Mastermind(
        codemakerId = alice,
        codebreakerId = bob,
        secret = Some(Vector(Peg.Red, Peg.Blue, Peg.Green, Peg.Yellow)),
        guesses = Nil,
        winner = None
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "guesses": [],
          "secret": ["red","blue","green","yellow"],
          "currentPlayer": "codebreaker",
          "guessesRemaining": 10,
          "viewerRole": "codemaker"
        }
      """)
    }
  }

  "Liar's Dice" should {
    "encode to its exact wire form, showing only the viewer's own dice" in {
      val game = LiarsDice(
        playerIds = Vector(alice, bob),
        dice = Vector(Vector(3, 3, 5), Vector(1, 2, 6)),
        currentSeat = 0,
        standing = Some(StandingBid(Bid(quantity = 3, face = Some(5)), bidderSeat = 1)),
        lastReveal = None,
        winner = None,
        moveCount = 4
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "dice": [3,3,5],
          "diceCounts": {"P1": 3, "P2": 3},
          "currentBid": {"quantity": 3, "face": 5},
          "currentPlayer": "P1",
          "viewerSeat": "P1"
        }
      """)
    }
  }

  "Texas Hold 'Em" should {
    "encode to its exact wire form, showing only the viewer's own hole cards" in {
      val game = TexasHoldEm(
        playerIds = Vector(alice, bob),
        stacks = Vector(980, 960),
        button = 0,
        holeCards = Vector(List(Card("AS"), Card("KD")), List(Card("7H"), Card("2C"))),
        board = List(Card("QS"), Card("JD"), Card("TC"), Card("9H"), Card("8S")),
        deck = Nil,
        street = Street.Flop,
        toAct = 1,
        streetContrib = Vector(20, 0),
        committed = Vector(40, 20),
        currentBet = 20,
        lastRaiseSize = 20,
        hasActed = Vector(true, false),
        folded = Vector(false, false),
        allIn = Vector(false, false),
        handResult = None,
        winner = None,
        moveCount = 6
      )

      wire(GameStateConverters.serializeGame(game, Some(alice))) shouldBe expected("""
        {
          "seats": [
            {"seat": "P1", "stack": 980, "committed": 40, "bet": 20, "folded": false, "allIn": false},
            {"seat": "P2", "stack": 960, "committed": 20, "bet": 0, "folded": false, "allIn": false}
          ],
          "holeCards": ["AS","KD"],
          "board": ["QS","JD","TC"],
          "button": "P1",
          "currentPlayer": "P2",
          "currentBet": 20,
          "minRaise": 40,
          "pot": 60,
          "toCall": 0,
          "street": "Flop",
          "viewerSeat": "P1"
        }
      """)
    }
  }
}
