// Battleship: a dual-board view. The viewer's own board reveals ships; the opponent's board (and both boards for a
// spectator) shows only fired cells, hiding ship positions until hit. Moves are ordinary cell clicks, so this reuses
// the shared `submitMove` from board.js rather than a bespoke action verb.
//
// Public surface: the renderer self-registers on GAMES.battleship.render (consumed by board.js).

import { $, session, GAMES } from "../state.js";
import { setStatus } from "../view.js";
import { submitMove } from "../board.js";

// Renders the dual-board Battleship view. The viewer's own board reveals ships; the opponent's board (and both boards
// for a spectator) shows only fired cells, hiding ship positions until hit.
export function renderBattleshipBoard(state) {
  const board = $("board");
  board.classList.add("bs-active");
  board.innerHTML = "";
  $("column-controls").innerHTML = "";

  const over = Boolean(state.winner);
  const spectating = Boolean(session.game && session.game.isSpectator);
  const myTurn = !spectating && !over && state.viewerSeat !== null && state.currentPlayer === state.viewerSeat;

  if (state.winner) setStatus(`${state.winner} wins!`);
  else if (state.viewerSeat) setStatus(myTurn ? "Your turn — pick a target!" : "Opponent's turn…");
  else setStatus(`Spectating — turn: ${state.currentPlayer}`);

  const wrapper = document.createElement("div");
  wrapper.className = "bs-boards";

  if (state.viewerSeat) {
    const myGrid  = state.viewerSeat === "P1" ? state.board1 : state.board2;
    const oppGrid = state.viewerSeat === "P1" ? state.board2 : state.board1;
    wrapper.appendChild(makeBsGrid("Your waters",  myGrid,  false));
    wrapper.appendChild(makeBsGrid("Enemy waters", oppGrid, myTurn));
  } else {
    wrapper.appendChild(makeBsGrid("Player 1", state.board1, false));
    wrapper.appendChild(makeBsGrid("Player 2", state.board2, false));
  }

  board.appendChild(wrapper);
}

// Builds one labeled Battleship grid. When `clickable` is true, only cells with token "unknown" (not yet fired) get
// click handlers — already-fired cells (hit/miss) are inert.
function makeBsGrid(label, rows, clickable) {
  const wrap = document.createElement("div");
  wrap.className = "bs-board-wrap";

  const lbl = document.createElement("div");
  lbl.className = "bs-label";
  lbl.textContent = label;
  wrap.appendChild(lbl);

  const grid = document.createElement("div");
  grid.className = "bs-grid";

  rows.forEach((cells, r) => {
    cells.forEach((token, c) => {
      const cell = document.createElement("div");
      const canClick = clickable && token === "unknown";
      cell.className = "cell" + (token ? ` mark-${token}` : "") + (canClick ? " clickable" : " disabled");
      if (canClick) cell.onclick = () => submitMove(r, c);
      grid.appendChild(cell);
    });
  });

  wrap.appendChild(grid);
  return wrap;
}

GAMES.battleship.render = renderBattleshipBoard;
