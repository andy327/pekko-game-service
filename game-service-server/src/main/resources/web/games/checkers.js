// Checkers: an 8×8 checkerboard where a move is a piece plus the squares it lands on. Unlike the one-click grid games,
// a move is built in two stages — pick up one of your pieces, then click where it goes — and a capture can chain across
// several jumps in a single move, so this is a bespoke view rather than the shared grid renderer.
//
// The server tags each view with the viewer's own color (`viewerSeat`), so the status line can say which side you are
// and whose turn it is, and the board only lets you pick up pieces on your own turn. The server stays the source of
// truth for legality (mandatory captures, complete jump chains); an illegal attempt surfaces its error and keeps the
// selection so the player can adjust.
//
// Public surface: the renderer self-registers on GAMES.checkers.render (consumed by board.js).

import { $, session, GAMES } from "../state.js";
import { setError, flashError } from "../view.js";
import { api } from "../api.js";

// The move being assembled: the picked-up piece's origin and the landing squares chosen so far (one per jump). Null
// when nothing is selected. Held at module scope so clicks between server pushes accumulate a multi-jump.
let selection = null; // { origin: {r, c}, path: [{r, c}, ...] }
let lastState = null;

const colorName = (token) => (token === "R" ? "Red" : "Black"); // seat/currentPlayer tokens are "R"/"B"
const chip = (token) => `<span class="ck-chip ck-chip-${token}"></span>`; // a small colored disc for the status line

// A fresh server push means the board changed underneath us, so any half-built move is stale — reset it and redraw.
export function renderCheckersBoard(state) {
  lastState = state;
  selection = null;
  draw();
}

// Draws the checkerboard from `lastState` and the in-progress `selection`. Called both on a server push and after each
// local click, so the highlight follows the selection without waiting for a round-trip.
function draw() {
  const state = lastState;
  const board = $("board");
  board.classList.add("checkers-active");
  board.style.setProperty("--cols", 8);
  board.innerHTML = "";
  $("column-controls").innerHTML = "";
  $("checkers-bar").innerHTML = "";

  const over = Boolean(state.winner);
  const me = state.viewerSeat; // "R" | "B" | null (spectator)
  const turn = state.currentPlayer; // "R" | "B"
  const myTurn = !over && me !== null && me === turn;

  renderStatus(state, me, turn, over);

  const stepKeys = new Set((selection ? selection.path : []).map((s) => `${s.r},${s.c}`));

  state.board.forEach((cells, r) => {
    cells.forEach((token, c) => {
      const dark = (r + c) % 2 === 1;
      const cell = document.createElement("div");
      let cls = "cell ck " + (dark ? "ck-dark" : "ck-light");
      if (token) cls += ` mark-${token}`;
      if (selection && selection.origin.r === r && selection.origin.c === c) cls += " ck-selected";
      if (stepKeys.has(`${r},${c}`)) cls += " ck-step";
      // You can only touch the dark squares, and only on your own turn.
      const clickable = myTurn && dark;
      cls += clickable ? " clickable" : " disabled";
      cell.className = cls;
      if (clickable) cell.onclick = () => onCellClick(r, c);
      board.appendChild(cell);
    });
  });

  // Once a jump has started, offer explicit submit/cancel so a multi-jump can be finished or abandoned.
  if (selection && selection.path.length) renderJumpControls();
}

// Writes the status line: who you are, and whether it's your move, the opponent's, or the game is over.
function renderStatus(state, me, turn, over) {
  const status = $("game-status");
  if (over) {
    const w = state.winner;
    if (me) status.innerHTML = `${chip(w)} ${w === me ? "You win!" : `You lose — ${colorName(w)} wins.`}`;
    else status.innerHTML = `${chip(w)} ${colorName(w)} wins!`;
  } else if (me === null) {
    status.innerHTML = `Spectating — ${chip(turn)} ${colorName(turn)} to move`;
  } else {
    const youAre = `${chip(me)} You are ${colorName(me)}`;
    status.innerHTML = me === turn ? `${youAre} — your move` : `${youAre} — waiting for ${colorName(turn)}`;
  }
}

// Handles a click on square (r, c): pick up a piece, extend a jump chain, or play a simple slide.
function onCellClick(r, c) {
  const state = lastState;
  const token = state.board[r][c];
  const mine = token && token.toUpperCase() === state.currentPlayer; // a piece of the side to move (i.e. yours)

  if (!selection) {
    if (mine) {
      selection = { origin: { r, c }, path: [] };
      draw();
    }
    return;
  }

  // Clicking the picked-up piece again clears the selection; clicking another of your pieces re-picks (before any jump).
  if (r === selection.origin.r && c === selection.origin.c && selection.path.length === 0) {
    selection = null;
    draw();
    return;
  }
  if (mine && selection.path.length === 0) {
    selection = { origin: { r, c }, path: [] };
    draw();
    return;
  }
  if (token !== "") return; // a move can only land on an empty square

  const from = selection.path.length ? selection.path[selection.path.length - 1] : selection.origin;
  const dr = Math.abs(r - from.r);
  const dc = Math.abs(c - from.c);

  if (dr === 1 && dc === 1 && selection.path.length === 0) {
    submitMove(selection.origin, [{ r, c }]); // a one-square diagonal slide is a complete move
  } else if (dr === 2 && dc === 2) {
    selection.path.push({ r, c }); // a jump; the chain may continue, so keep the selection open
    draw();
  }
  // any other click is not a legal diagonal step — ignore it
}

// Submit/cancel controls, rendered into the reserved strip below the board so they never shift the board itself.
function renderJumpControls() {
  const wrap = document.createElement("div");
  wrap.className = "checkers-controls";

  const submit = document.createElement("button");
  submit.className = "ck-btn ck-submit";
  submit.textContent = "Submit move";
  submit.onclick = () => submitMove(selection.origin, selection.path.slice());

  const cancel = document.createElement("button");
  cancel.className = "ck-btn ck-cancel";
  cancel.textContent = "Cancel";
  cancel.onclick = () => {
    selection = null;
    draw();
  };

  wrap.appendChild(submit);
  wrap.appendChild(cancel);
  $("checkers-bar").appendChild(wrap);
}

// POSTs the assembled move as { from, steps } of {row, col} squares. A successful move re-renders via the WebSocket
// push (which resets the selection); a rejected one surfaces its error and keeps the selection so the player can adjust.
async function submitMove(origin, steps) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const body = {
    from: { row: origin.r, col: origin.c },
    steps: steps.map((s) => ({ row: s.r, col: s.c }))
  };
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}

GAMES.checkers.render = renderCheckersBoard;
