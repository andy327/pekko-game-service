// Board rendering entry point and the shared grid renderer.
//
// `renderBoard` runs on every GameStateUpdated push. Games with a bespoke view register a `render(state)` on their
// GAMES entry when their module loads (see games/*.js); renderBoard dispatches to it. Everything without one — the
// plain grid games, Tic-Tac-Toe and Connect Four — is drawn by the shared renderer here. `submitMove` is the generic
// cell/column move used by the grid games and Battleship (each game with its own action verbs POSTs directly instead).

import { $, session, GAMES } from "./state.js";
import { setStatus, setError, flashError } from "./view.js";
import { api } from "./api.js";

// Renders any game-state view pushed from the server: dispatches to a game's registered renderer, or falls back to the
// shared grid renderer below for cell/column games.
export function renderBoard(state) {
  // a board push means we're now (or still) watching a live game, however we got here
  if (session.game) session.game.isLive = true;
  $("start-game").classList.add("hidden");
  $("post-game-bar").classList.add("hidden");
  $("rematch-btn").classList.add("hidden");
  setError("game-error", "");
  $("board").classList.remove("ld-active"); // cleared here so switching away from Liar's Dice restores the board grid
  $("board").classList.remove("holdem-active"); // likewise for the free-sized Texas Hold 'Em table
  $("board").classList.remove("c4-active"); // and the blue Connect Four board; re-added below only for column games
  $("board").classList.remove("pig-active"); // and the free-flowing Pig layout
  $("board").classList.remove("checkers-active"); // and the Checkers checkerboard

  // Games with a custom board install a renderer on their GAMES entry (games/*.js); the rest fall through to the shared
  // grid renderer below.
  const game = session.game && GAMES[session.game.gameType];
  if (game && game.render) {
    game.render(state);
    return;
  }

  // Grid games (TicTacToe, ConnectFour): a single flat board of cell tokens.
  const board = $("board");
  board.classList.remove("bs-active");
  const rows = state.board;
  const cols = rows[0] ? rows[0].length : 0;
  board.style.setProperty("--cols", cols);
  board.innerHTML = "";

  const over = Boolean(state.winner) || state.draw === true;
  const spectating = Boolean(session.game && session.game.isSpectator);
  const columnMode = Boolean(session.game && GAMES[session.game.gameType] && GAMES[session.game.gameType].columns);

  // Column games (Connect Four) get the blue slotted-board look and disc pieces; the letters are hidden by CSS.
  board.classList.toggle("c4-active", columnMode);

  renderColumnControls(columnMode ? cols : 0, rows, over || spectating, state.currentPlayer);

  rows.forEach((cells, r) => {
    cells.forEach((mark, c) => {
      const cell = document.createElement("div");
      // In column mode the cells are display-only; you drop via the buttons above the board.
      const clickable = !over && !spectating && !columnMode;
      cell.className = "cell" + (mark ? ` mark-${mark}` : "") + (clickable ? " clickable" : " disabled");
      cell.textContent = mark;
      if (clickable) cell.onclick = () => submitMove(r, c);
      board.appendChild(cell);
    });
  });

  if (state.winner) setStatus(`${state.winner} wins!`);
  else if (state.draw) setStatus("Draw.");
  else setStatus(`Turn: ${state.currentPlayer}`);
}

// Render one drop button per column above the board (for column-based games). Pass cols=0 to clear the controls for
// cell-click games. A column whose top cell is filled is full, so its button is disabled. `currentMark` (the token
// about to drop, e.g. "R"/"Y") tints the arrows so you can see whose disc you're placing.
function renderColumnControls(cols, rows, over, currentMark) {
  const controls = $("column-controls");
  controls.innerHTML = "";
  if (cols === 0) return;
  controls.style.setProperty("--cols", cols);
  for (let c = 0; c < cols; c++) {
    const btn = document.createElement("button");
    btn.className = "col-btn" + (currentMark ? ` col-btn-${currentMark}` : "");
    btn.textContent = "▼";
    btn.disabled = over || (rows[0] && rows[0][c] !== ""); // column full when its top cell is occupied
    btn.onclick = () => submitMove(0, c); // row is ignored for column moves
    controls.appendChild(btn);
  }
}

// The generic cell/column move: turns a (row, col) click into the game's move payload (via GAMES[type].move) and POSTs
// it. Used by grid games and Battleship. Successful moves redraw via the WebSocket push; only failures surface here.
export async function submitMove(row, col) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const payload = GAMES[game.gameType].move(row, col);
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body: payload });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}
