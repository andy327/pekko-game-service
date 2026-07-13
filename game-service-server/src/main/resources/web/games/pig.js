// Pig: a press-your-luck dice game. Each turn you Roll to accumulate a turn score (busting on a 1) or Hold to bank it.
// A score table, turn info, and Roll / Hold buttons for the active player.
//
// Public surface: the renderer self-registers on GAMES.pig.render (consumed by board.js). Everything else is private.

import { $, session, GAMES } from "../state.js";
import { setStatus, setError, flashError } from "../view.js";
import { api } from "../api.js";

// A die drawn as CSS pips — a 3×3 grid with dots at the face's positions. `face` is 1–6; a rolled 1 busts the turn, so
// it's drawn in red to make the bad news obvious.
const PIG_PIPS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };

function makePigDie(face) {
  const die = document.createElement("span");
  die.className = "pig-die" + (face === 1 ? " pig-die-bust" : "");
  const pips = new Set(PIG_PIPS[face] || []);
  for (let i = 0; i < 9; i++) {
    const cell = document.createElement("span");
    cell.className = "pig-pipcell";
    if (pips.has(i)) {
      const dot = document.createElement("span");
      dot.className = "pig-pip";
      cell.appendChild(dot);
    }
    die.appendChild(cell);
  }
  return die;
}

// Sends a Pig action ("roll" or "hold") as a POST move to the server.
async function submitPigAction(action) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body: { action } });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}

// Renders a Pig game state: a score table, turn info, and Roll / Hold action buttons.
export function renderPigBoard(state) {
  const board = $("board");
  board.classList.remove("bs-active");
  board.classList.add("pig-active"); // free the board from the fixed cell grid so the table and controls lay out normally
  board.innerHTML = "";
  $("column-controls").innerHTML = "";

  const over = Boolean(state.winner);
  const spectating = Boolean(session.game && session.game.isSpectator);
  const myTurn = !spectating && !over && state.viewerSeat !== null && state.currentPlayer === state.viewerSeat;

  if (state.winner) setStatus(`${state.winner} wins!`);
  else if (state.viewerSeat) setStatus(myTurn ? "Your turn!" : `${state.currentPlayer}'s turn…`);
  else setStatus(`Spectating — turn: ${state.currentPlayer}`);

  // Score table
  const table = document.createElement("table");
  table.className = "pig-scores";
  const header = table.insertRow();
  header.innerHTML = "<th>Player</th><th>Score</th>";
  for (const [seat, score] of Object.entries(state.scores).sort()) {
    const row = table.insertRow();
    const isCurrentPlayer = seat === state.currentPlayer;
    row.className = isCurrentPlayer && !over ? "pig-current" : "";
    row.innerHTML = `<td>${seat}${seat === state.viewerSeat ? " (you)" : ""}</td><td>${score}</td>`;
  }
  board.appendChild(table);

  // Turn info: the running turn score alongside the last roll, shown as an actual die face.
  const info = document.createElement("div");
  info.className = "pig-turn-info";

  const turnText = document.createElement("span");
  turnText.textContent = `Turn score: ${state.turnScore}`;
  info.appendChild(turnText);

  const rollWrap = document.createElement("span");
  rollWrap.className = "pig-last-roll";
  if (state.lastRoll != null) {
    const label = document.createElement("span");
    label.className = "pig-roll-label";
    label.textContent = "Last roll:";
    rollWrap.appendChild(label);
    rollWrap.appendChild(makePigDie(state.lastRoll));
  } else {
    rollWrap.textContent = "No roll yet this turn";
  }
  info.appendChild(rollWrap);
  board.appendChild(info);

  // Action buttons (only for the active player)
  if (myTurn) {
    const actions = document.createElement("div");
    actions.className = "pig-actions";

    const rollBtn = document.createElement("button");
    rollBtn.textContent = "Roll";
    rollBtn.onclick = () => submitPigAction("roll");
    actions.appendChild(rollBtn);

    const holdBtn = document.createElement("button");
    holdBtn.textContent = "Hold";
    holdBtn.disabled = state.turnScore === 0;
    holdBtn.onclick = () => submitPigAction("hold");
    actions.appendChild(holdBtn);

    board.appendChild(actions);
  }
}

GAMES.pig.render = renderPigBoard;
