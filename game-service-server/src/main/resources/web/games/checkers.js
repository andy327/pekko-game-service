// Checkers: an 8×8 checkerboard where a move is a piece plus the squares it lands on. Unlike the one-click grid games,
// a move is built in two stages — pick up one of the side-to-move's pieces, then click where it goes — and a capture
// can chain across several jumps in a single move, so this is a bespoke view rather than the shared grid renderer.
//
// The board only ever shows the side to move's pieces as pickable; the server is the source of truth for legality
// (mandatory captures, complete jump chains, turn order), so an illegal attempt just surfaces its error and the
// selection is kept so the player can adjust.
//
// Public surface: the renderer self-registers on GAMES.checkers.render (consumed by board.js).

import { $, session, GAMES } from "../state.js";
import { setStatus, setError, flashError } from "../view.js";
import { api } from "../api.js";

// The move being assembled: the picked-up piece's origin and the landing squares chosen so far (one per jump). Null
// when nothing is selected. Held at module scope so clicks between server pushes accumulate a multi-jump.
let selection = null; // { origin: {r, c}, path: [{r, c}, ...] }
let lastState = null;

const colorName = (token) => (token === "R" ? "Red" : "Black"); // currentPlayer/winner tokens are "R"/"B"

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

  const over = Boolean(state.winner) || state.draw === true;
  const spectating = Boolean(session.game && session.game.isSpectator);
  const turn = state.currentPlayer;

  if (state.winner) setStatus(`${colorName(state.winner)} wins!`);
  else if (spectating) setStatus(`Spectating — turn: ${colorName(turn)}`);
  else setStatus(`Turn: ${colorName(turn)}`);

  const stepKeys = new Set((selection ? selection.path : []).map((s) => `${s.r},${s.c}`));

  state.board.forEach((cells, r) => {
    cells.forEach((token, c) => {
      const dark = (r + c) % 2 === 1;
      const cell = document.createElement("div");
      let cls = "cell ck " + (dark ? "ck-dark" : "ck-light");
      if (token) cls += ` mark-${token}`;
      if (selection && selection.origin.r === r && selection.origin.c === c) cls += " ck-selected";
      if (stepKeys.has(`${r},${c}`)) cls += " ck-step";
      // Only the dark squares are ever in play; light squares and a finished/spectated game are inert.
      const clickable = !over && !spectating && dark;
      cls += clickable ? " clickable" : " disabled";
      cell.className = cls;
      if (clickable) cell.onclick = () => onCellClick(r, c);
      board.appendChild(cell);
    });
  });

  // Once a jump has started, offer explicit submit/cancel so a multi-jump can be finished or abandoned.
  if (selection && selection.path.length) renderJumpControls();
}

// Handles a click on square (r, c): pick up a piece, extend a jump chain, or play a simple slide.
function onCellClick(r, c) {
  const state = lastState;
  const token = state.board[r][c];
  const mine = token && token.toUpperCase() === state.currentPlayer; // a piece of the side to move

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

// Submit/cancel controls shown while a jump chain is being built.
function renderJumpControls() {
  const wrap = document.createElement("div");
  wrap.className = "checkers-controls";

  const submit = document.createElement("button");
  submit.className = "ck-btn";
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
  $("column-controls").appendChild(wrap);
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
