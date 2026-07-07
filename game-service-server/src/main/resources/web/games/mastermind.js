// Mastermind: the codemaker sets a hidden 4-peg code, then the codebreaker guesses it, receiving black/white peg
// feedback each turn. `state.secret` is only populated when the server chooses to reveal it — to the codemaker, or to
// everyone once the game ends — so the codebreaker never sees the answer early.
//
// The peg-picker keeps a local draft (the pegs chosen but not yet submitted) that survives re-renders and resets when
// the room changes. Public surface: the renderer self-registers on GAMES.mastermind.render (consumed by board.js).

import { $, session, GAMES } from "../state.js";
import { setStatus, setError, flashError } from "../view.js";
import { api } from "../api.js";

const MM_COLORS = ["red", "yellow", "blue", "green", "black", "white"];
const MM_CODE_LENGTH = 4;
let mastermindDraft = []; // colors picked for the pending code/guess
let mastermindDraftRoom = null; // the room the draft belongs to, so switching games starts fresh
let lastMastermindState = null; // most recent state, re-rendered on local peg-picker interactions

// Sends the codemaker's "setcode" or the codebreaker's "guess" as a POST move; clears the draft on success.
async function submitMastermindMove(action, pegs) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body: { action, pegs } });
  if (res.ok) mastermindDraft = [];
  else flashError("game-error", res.data?.error || res.data || "Move rejected");
}

// A single colored peg (or an empty slot when `color` is null).
function makeMmPeg(color) {
  const peg = document.createElement("span");
  peg.className = "mm-peg" + (color ? ` mm-${color}` : " mm-empty");
  return peg;
}

// Feedback dots for a scored guess: `black` dark dots, then `white` light dots, padded to the code length.
function makeMmFeedback(black, white) {
  const wrap = document.createElement("span");
  wrap.className = "mm-feedback";
  for (let i = 0; i < MM_CODE_LENGTH; i++) {
    const kind = i < black ? "black" : i < black + white ? "white" : "none";
    const dot = document.createElement("span");
    dot.className = `mm-dot mm-dot-${kind}`;
    wrap.appendChild(dot);
  }
  return wrap;
}

export function renderMastermindBoard(state) {
  // A draft belongs to one room; entering a different game starts with empty slots.
  if (session.game && session.game.roomId !== mastermindDraftRoom) {
    mastermindDraft = [];
    mastermindDraftRoom = session.game.roomId;
  }
  lastMastermindState = state;

  const board = $("board");
  board.classList.remove("bs-active");
  board.innerHTML = "";
  $("column-controls").innerHTML = "";

  const over = Boolean(state.winner);
  const role = state.viewerRole; // "codemaker" | "codebreaker" | null (spectator)
  const spectating = Boolean(session.game && session.game.isSpectator) || role === null;
  const iAmCodemaker = role === "codemaker";
  const iAmCodebreaker = role === "codebreaker";
  const needsCode = !state.secret && state.currentPlayer === "codemaker"; // codemaker has not set the code yet
  const myTurn =
    !over && !spectating &&
    ((iAmCodemaker && needsCode) || (iAmCodebreaker && state.currentPlayer === "codebreaker"));

  // Status line
  if (over) setStatus(state.winner === role ? "You win!" : `The ${state.winner} wins!`);
  else if (iAmCodemaker) setStatus(needsCode ? "Set your secret code." : "Code set — waiting for the codebreaker…");
  else if (iAmCodebreaker) setStatus(myTurn ? "Your turn — guess the code!" : "Waiting…");
  else setStatus(`Spectating — ${state.currentPlayer} to move`);

  // Revealed secret (the codemaker always sees it; everyone sees it once the game ends)
  if (state.secret) {
    const secret = document.createElement("div");
    secret.className = "mm-secret mm-row";
    const label = document.createElement("span");
    label.className = "mm-label";
    label.textContent = over ? "Secret:" : "Your code:";
    secret.appendChild(label);
    state.secret.forEach((c) => secret.appendChild(makeMmPeg(c)));
    board.appendChild(secret);
  }

  // Public guess history, oldest first, each with its feedback
  const history = document.createElement("div");
  history.className = "mm-history";
  state.guesses.forEach((g, i) => {
    const row = document.createElement("div");
    row.className = "mm-row mm-guess";
    const num = document.createElement("span");
    num.className = "mm-num";
    num.textContent = `${i + 1}.`;
    row.appendChild(num);
    g.pegs.forEach((c) => row.appendChild(makeMmPeg(c)));
    row.appendChild(makeMmFeedback(g.black, g.white));
    history.appendChild(row);
  });
  board.appendChild(history);

  if (!over) {
    const remaining = document.createElement("p");
    remaining.className = "mm-remaining";
    remaining.textContent = `Guesses remaining: ${state.guessesRemaining}`;
    board.appendChild(remaining);
  }

  if (myTurn) board.appendChild(makeMmInput(needsCode ? "setcode" : "guess"));
}

// The peg-picker: four slots filled from a color palette, plus Submit / Clear. Clicking a filled slot removes that peg.
function makeMmInput(action) {
  const rerender = () => renderMastermindBoard(lastMastermindState);

  const wrap = document.createElement("div");
  wrap.className = "mm-input";

  const slots = document.createElement("div");
  slots.className = "mm-row mm-slots";
  for (let i = 0; i < MM_CODE_LENGTH; i++) {
    const peg = makeMmPeg(mastermindDraft[i] || null);
    if (mastermindDraft[i]) {
      peg.classList.add("mm-clickable");
      peg.onclick = () => { mastermindDraft.splice(i, 1); rerender(); };
    }
    slots.appendChild(peg);
  }
  wrap.appendChild(slots);

  const palette = document.createElement("div");
  palette.className = "mm-row mm-palette";
  MM_COLORS.forEach((color) => {
    const swatch = makeMmPeg(color);
    swatch.classList.add("mm-clickable");
    swatch.onclick = () => {
      if (mastermindDraft.length < MM_CODE_LENGTH) { mastermindDraft.push(color); rerender(); }
    };
    palette.appendChild(swatch);
  });
  wrap.appendChild(palette);

  const actions = document.createElement("div");
  actions.className = "row mm-actions";
  const submit = document.createElement("button");
  submit.textContent = action === "setcode" ? "Set code" : "Guess";
  submit.disabled = mastermindDraft.length !== MM_CODE_LENGTH;
  submit.onclick = () => submitMastermindMove(action, mastermindDraft.slice());
  actions.appendChild(submit);

  const clear = document.createElement("button");
  clear.textContent = "Clear";
  clear.disabled = mastermindDraft.length === 0;
  clear.onclick = () => { mastermindDraft = []; rerender(); };
  actions.appendChild(clear);

  wrap.appendChild(actions);
  return wrap;
}

GAMES.mastermind.render = renderMastermindBoard;
