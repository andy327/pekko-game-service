// Texas Hold 'Em view: the community board, a seat table (stacks, bets, folded/all-in), the viewer's own hole cards,
// the last hand's showdown reveal, and betting controls. The server validates every bet/raise amount and rejects an
// illegal one.
//
// Public surface: the renderer self-registers on GAMES.texasholdem.render (consumed by board.js), plus
// resetHoldemPreAction() which lobby.js calls when preparing a fresh game view. Everything else here (pre-action
// arming, controls, card faces) is private to this module.

import { $, session, GAMES } from "../state.js";
import { api } from "../api.js";
import { setStatus, setError, flashError } from "../view.js";

const HOLDEM_SUITS = { S: "♠", H: "♥", C: "♣", D: "♦" };

// The Hold 'Em pre-action ("advance action") the viewer has armed while waiting for the turn to reach them, or null.
// It is a client-only convenience — nothing is sent until it is the viewer's turn, at which point the armed choice is
// auto-submitted as a normal in-turn move. Shape: { kind, street, button, currentBet, toCall } where the last four are
// the betting context captured when it was armed, used to invalidate a choice the action has since outrun.
let holdemPreAction = null;

// Drop any armed pre-action — called when entering a fresh game view (lobby.js) so a choice armed in a previous game
// doesn't carry over.
export function resetHoldemPreAction() {
  holdemPreAction = null;
}

export function renderTexasHoldEmBoard(state) {
  const board = $("board");
  board.classList.remove("bs-active");
  board.classList.add("holdem-active"); // the seat table is wider than the default board grid; let it size freely
  board.innerHTML = "";
  $("column-controls").innerHTML = "";

  const over = Boolean(state.winner);
  const spectating = Boolean(session.game && session.game.isSpectator) || state.viewerSeat == null;
  const myTurn = !over && !spectating && state.currentPlayer === state.viewerSeat;

  if (over) setStatus(state.winner === state.viewerSeat ? "You win the sit-and-go!" : `${state.winner} wins the sit-and-go!`);
  else if (spectating) setStatus(`Spectating — ${state.currentPlayer} to act`);
  else setStatus(myTurn ? "Your turn to act." : `${state.currentPlayer}'s turn…`);

  // The community cards (revealed cards face-up, the rest face-down) and the pot. Once the sit-and-go is over no new
  // hand is dealt, so the top table still refers to the final hand — show its full showdown run-out here rather than
  // the street-limited live board (which stalls face-down when players get all-in early).
  const topBoard = over && state.handResult ? state.handResult.board : state.board;
  const cards = document.createElement("div");
  cards.className = "holdem-board";
  for (let i = 0; i < 5; i++) cards.appendChild(makeHoldemCard(i < topBoard.length ? topBoard[i] : null));
  board.appendChild(cards);

  const pot = document.createElement("div");
  pot.className = "holdem-pot";
  pot.textContent = `Pot: ${state.pot}` + (state.currentBet ? ` · Current bet: ${state.currentBet}` : "");
  board.appendChild(pot);

  // Seats: stack, chips committed this hand, and whose turn it is.
  const table = document.createElement("table");
  table.className = "holdem-seats";
  table.insertRow().innerHTML = "<th>Player</th><th>Stack</th><th>In pot</th><th></th>";
  state.seats.forEach((s) => {
    const row = table.insertRow();
    row.className = s.seat === state.currentPlayer && !over ? "holdem-current" : "";
    const you = s.seat === state.viewerSeat ? " (you)" : "";
    const dealer = s.seat === state.button ? " Ⓓ" : ""; // circled D marks the dealer button
    const status = s.folded ? "folded" : s.allIn ? "all-in" : s.bet > 0 ? `bet ${s.bet}` : "";
    row.innerHTML = `<td>${s.seat}${you}${dealer}</td><td>${s.stack}</td><td>${s.committed}</td><td>${status}</td>`;
  });
  board.appendChild(table);

  // The most recent hand's showdown reveal — the one moment hole cards come up. Its board row is shown only mid-game,
  // when the top strip has already moved on to the next hand; once the game is over the top strip shows this board.
  if (state.handResult) board.appendChild(renderHoldemResult(state.handResult, !over));

  // The viewer's own hole cards.
  if (state.holeCards && state.holeCards.length) {
    const hand = document.createElement("div");
    hand.className = "holdem-hand";
    const label = document.createElement("span");
    label.className = "holdem-label";
    label.textContent = "Your hand:";
    hand.appendChild(label);
    state.holeCards.forEach((c) => hand.appendChild(makeHoldemCard(c)));
    board.appendChild(hand);
  }

  const me = state.seats.find((s) => s.seat === state.viewerSeat);
  const canPreAct = !over && !spectating && Boolean(me) && !me.folded && !me.allIn;

  if (myTurn) {
    // Fire a still-valid armed pre-action as this turn's move; the manual controls stay so a rejected auto-move (or a
    // change of mind) leaves the player able to act. The pre-action is consumed either way.
    const armed = holdemPreAction;
    holdemPreAction = null;
    if (armed) {
      const move = holdemPreActionMove(state, armed);
      if (move) submitTexasHoldEmMove(move);
    }
    board.appendChild(makeHoldemControls(state));
  } else {
    reconcileHoldemPreAction(state, canPreAct);
    if (canPreAct) board.appendChild(makeHoldemPreActions(state));
  }
}

// A single card face from its text form ("AS", "TD"), or a face-down card for a null (unrevealed) slot.
function makeHoldemCard(card) {
  const el = document.createElement("span");
  el.className = "holdem-card";
  if (!card) {
    el.classList.add("holdem-card-back");
    return el;
  }
  const suit = card.slice(-1);
  if (suit === "H" || suit === "D") el.classList.add("holdem-card-red");
  el.innerHTML =
    `<span class="holdem-rank">${card.slice(0, -1)}</span>` +
    `<span class="holdem-suit">${HOLDEM_SUITS[suit] || suit}</span>`;
  return el;
}

// The showdown reveal: who won each pot and with what, plus the hole cards of everyone who reached the showdown.
function renderHoldemResult(result, showBoard) {
  const box = document.createElement("div");
  box.className = "holdem-reveal";

  // The full five-card run-out at the showdown, including streets that never came up in the live board when the hand
  // ended early (e.g. everyone all-in before the river). Shown only mid-game; once the game is over the top strip
  // carries this board instead, so rendering it here too would just duplicate it.
  if (showBoard && result.board && result.board.length) {
    const cards = document.createElement("div");
    cards.className = "holdem-board holdem-reveal-board";
    result.board.forEach((c) => cards.appendChild(makeHoldemCard(c)));
    box.appendChild(cards);
  }

  result.awards.forEach((a) => {
    const p = document.createElement("p");
    p.className = "holdem-reveal-summary";
    const who = a.winners.join(", ");
    p.textContent = a.description ? `${who} won ${a.amount} with ${a.description}.` : `${who} won ${a.amount}.`;
    box.appendChild(p);
  });
  Object.keys(result.shownHands).sort().forEach((seat) => {
    const row = document.createElement("div");
    row.className = "holdem-hand";
    const label = document.createElement("span");
    label.className = "holdem-label";
    label.textContent = `${seat}:`;
    row.appendChild(label);
    result.shownHands[seat].forEach((c) => row.appendChild(makeHoldemCard(c)));
    box.appendChild(row);
  });
  return box;
}

// Fold, check-or-call, and a bet/raise amount (the total to commit this street) with an all-in shortcut. The bet/raise
// row is hidden when the viewer cannot raise; a short all-in below the minimum raise is still offered as All-in.
function makeHoldemControls(state) {
  const wrap = document.createElement("div");
  wrap.className = "holdem-controls";

  const me = state.seats.find((s) => s.seat === state.viewerSeat) || { stack: 0, bet: 0 };
  const maxTo = me.bet + me.stack; // the total this street if the viewer moves all-in

  const row = document.createElement("div");
  row.className = "row";

  const fold = document.createElement("button");
  fold.textContent = "Fold";
  fold.onclick = () => submitTexasHoldEmMove({ action: "fold" });
  row.appendChild(fold);

  const callBtn = document.createElement("button");
  if (state.toCall === 0) {
    callBtn.textContent = "Check";
    callBtn.onclick = () => submitTexasHoldEmMove({ action: "check" });
  } else {
    callBtn.textContent = `Call ${Math.min(state.toCall, me.stack)}`;
    callBtn.onclick = () => submitTexasHoldEmMove({ action: "call" });
  }
  row.appendChild(callBtn);
  wrap.appendChild(row);

  if (maxTo > state.currentBet) {
    const opening = state.currentBet === 0;
    const betRow = document.createElement("div");
    betRow.className = "row holdem-bet-row";

    if (maxTo >= state.minRaise) {
      const amount = document.createElement("input");
      amount.type = "number";
      amount.className = "holdem-amount";
      amount.min = String(state.minRaise);
      amount.max = String(maxTo);
      amount.value = String(state.minRaise);
      betRow.appendChild(amount);

      const betBtn = document.createElement("button");
      betBtn.textContent = opening ? "Bet" : "Raise to";
      betBtn.onclick = () => {
        const value = parseInt(amount.value, 10);
        if (!Number.isInteger(value) || value < 1) {
          flashError("game-error", "Enter a valid amount.");
          return;
        }
        submitTexasHoldEmMove({ action: opening ? "bet" : "raise", amount: value });
      };
      betRow.appendChild(betBtn);
    }

    const allIn = document.createElement("button");
    allIn.textContent = `All-in ${maxTo}`;
    allIn.onclick = () => submitTexasHoldEmMove({ action: opening ? "bet" : "raise", amount: maxTo });
    betRow.appendChild(allIn);

    wrap.appendChild(betRow);
  }

  return wrap;
}

async function submitTexasHoldEmMove(body) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}

// The concrete move an armed pre-action becomes now that it is the viewer's turn, or null if the betting has changed
// enough that the choice no longer applies (e.g. a plain Check or an exact Call after someone raised) and should be
// dropped rather than fired. Check/Fold and Call Any are conditional by design and always resolve to a legal move.
function holdemPreActionMove(state, pa) {
  switch (pa.kind) {
    case "fold":       return { action: "fold" };
    case "check-fold": return state.toCall === 0 ? { action: "check" } : { action: "fold" };
    case "check":      return state.toCall === 0 ? { action: "check" } : null;
    case "call":       return state.toCall > 0 && state.toCall === pa.toCall ? { action: "call" } : null;
    case "call-any":   return state.toCall > 0 ? { action: "call" } : { action: "check" };
    default:           return null;
  }
}

// Drops an armed pre-action the betting has outrun before the viewer's turn: a new street or hand (button moved), the
// viewer no longer able to act, or — for the amount-specific Check and exact Call — any change to the current bet.
function reconcileHoldemPreAction(state, canPreAct) {
  const pa = holdemPreAction;
  if (!pa) return;
  const betMoved = (pa.kind === "check" || pa.kind === "call") && state.currentBet !== pa.currentBet;
  if (!canPreAct || state.street !== pa.street || state.button !== pa.button || betMoved) holdemPreAction = null;
}

// The pre-action toggles offered while it is not the viewer's turn: Check/Fold + Check when they can currently check,
// otherwise Fold + exact Call + Call Any. Clicking one arms it (capturing the betting context); clicking it again, or
// choosing another, re-arms. The armed toggle is highlighted; it fires automatically once the turn arrives.
function makeHoldemPreActions(state) {
  const wrap = document.createElement("div");
  wrap.className = "holdem-preactions";

  const label = document.createElement("div");
  label.className = "holdem-preaction-label";
  label.textContent = "Pre-select your move — fires when it's your turn:";
  wrap.appendChild(label);

  const row = document.createElement("div");
  row.className = "row";

  const me = state.seats.find((s) => s.seat === state.viewerSeat) || { stack: 0 };
  const kinds =
    state.toCall === 0
      ? [["check-fold", "Check/Fold"], ["check", "Check"]]
      : [["fold", "Fold"], ["call", `Call ${Math.min(state.toCall, me.stack)}`], ["call-any", "Call Any"]];

  kinds.forEach(([kind, text]) => {
    const btn = document.createElement("button");
    btn.textContent = text;
    btn.className = "holdem-preaction";
    if (holdemPreAction && holdemPreAction.kind === kind) btn.classList.add("armed");
    btn.onclick = () => {
      holdemPreAction =
        holdemPreAction && holdemPreAction.kind === kind
          ? null
          : { kind, street: state.street, button: state.button, currentBet: state.currentBet, toCall: state.toCall };
      renderTexasHoldEmBoard(state); // rebuild so the highlighted toggle reflects the new armed choice
    };
    row.appendChild(btn);
  });

  wrap.appendChild(row);
  return wrap;
}

GAMES.texasholdem.render = renderTexasHoldEmBoard;
