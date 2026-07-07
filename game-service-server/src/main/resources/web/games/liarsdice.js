// Liar's Dice: each player hides five dice; on your turn you either raise the bid (a quantity + face over all dice on
// the table, with 1s wild) or call "Liar". `state.dice` is only your own hand — other seats show just a count — until a
// challenge reveals every die in `state.lastReveal`, the one public moment.
//
// The centrepiece is a Monopoly-style bidding-track ring rendered from CSS-pip dice. Public surface: the renderer
// self-registers on GAMES.liarsdice.render (consumed by board.js).

import { $, session, GAMES } from "../state.js";
import { setStatus, setError, flashError } from "../view.js";
import { api } from "../api.js";

// A die drawn as CSS pips — clearer and larger than a unicode glyph. `face` is 1–6; `opts.red` draws the bid marker
// (white pips on red), `opts.small` a compact die for the reveal rows and the track marker.
const LD_PIPS = { 1: [4], 2: [0, 8], 3: [0, 4, 8], 4: [0, 2, 6, 8], 5: [0, 2, 4, 6, 8], 6: [0, 2, 3, 5, 6, 8] };

function makeLdDie(face, opts = {}) {
  const die = document.createElement("span");
  die.className = "ld-die" + (opts.red ? " ld-die-red" : "") + (opts.small ? " ld-die-sm" : "");
  const pips = new Set(LD_PIPS[face] || []);
  for (let i = 0; i < 9; i++) {
    const cell = document.createElement("span");
    cell.className = "ld-pipcell";
    if (pips.has(i)) {
      const dot = document.createElement("span");
      dot.className = "ld-pip";
      cell.appendChild(dot);
    }
    die.appendChild(cell);
  }
  return die;
}

// The bidding track in clockwise order: quantities 1–20 with a wild "k ones" space after each odd quantity 2k−1,
// exactly where the physical mat prints them, so the ones spaces are visible in relation to the numbered ones.
function ldTrackSpaces() {
  const spaces = [];
  for (let q = 1; q <= 20; q++) {
    spaces.push({ kind: "num", quantity: q });
    if (q % 2 === 1) spaces.push({ kind: "ones", quantity: (q + 1) / 2 });
  }
  return spaces; // 20 numbered + 10 ones = 30
}

// Perimeter cell positions for a 10×7 grid, clockwise from the top-left — 30 cells, one per track space, leaving the
// interior free for the current-bid summary.
function ldRingPositions() {
  const W = 10, H = 7, pos = [];
  for (let c = 1; c <= W; c++) pos.push({ r: 1, c }); // top, left → right
  for (let r = 2; r <= H; r++) pos.push({ r, c: W }); // right, top → bottom
  for (let c = W - 1; c >= 1; c--) pos.push({ r: H, c }); // bottom, right → left
  for (let r = H - 1; r >= 2; r--) pos.push({ r, c: 1 }); // left, bottom → top
  return pos;
}

// The track-space index the current bid sits on, or -1 when there is no standing bid.
function ldCurrentSpaceIndex(spaces, bid) {
  if (!bid) return -1;
  const kind = bid.face == null ? "ones" : "num";
  return spaces.findIndex((s) => s.kind === kind && s.quantity === bid.quantity);
}

// The Monopoly-style bidding-track ring, marking the current bid with a red die (its face for a numbered bid, a single
// pip for a wild ones bid); the ring's centre carries the current-bid summary.
function renderLdTrack(state) {
  const spaces = ldTrackSpaces();
  const positions = ldRingPositions();
  const currentIndex = ldCurrentSpaceIndex(spaces, state.currentBid);

  const track = document.createElement("div");
  track.className = "ld-track";

  spaces.forEach((space, i) => {
    const cell = document.createElement("div");
    cell.className = space.kind === "ones" ? "ld-space ld-space-ones" : "ld-space ld-space-num";
    cell.style.gridColumn = String(positions[i].c);
    cell.style.gridRow = String(positions[i].r);

    const label = document.createElement("span");
    label.className = "ld-space-label";
    label.textContent = String(space.quantity);
    cell.appendChild(label);
    if (space.kind === "ones") {
      const pip = document.createElement("span");
      pip.className = "ld-onepip"; // marks a wild "ones" space
      cell.appendChild(pip);
    }

    if (i === currentIndex) {
      cell.classList.add("ld-space-current");
      const face = state.currentBid.face == null ? 1 : state.currentBid.face;
      const marker = makeLdDie(face, { red: true, small: true });
      marker.classList.add("ld-marker");
      cell.appendChild(marker);
    }
    track.appendChild(cell);
  });

  const center = document.createElement("div");
  center.className = "ld-track-center";
  center.textContent = state.currentBid ? `Current bid: ${formatLdBid(state.currentBid)}` : "No bid yet this round";
  track.appendChild(center);

  return track;
}

// "3 × 4s" for a numbered bid, "2 × ones" for a wild ones bid, or a dash when there is no bid.
function formatLdBid(bid) {
  if (!bid) return "—";
  return bid.face == null ? `${bid.quantity} × ones` : `${bid.quantity} × ${bid.face}s`;
}

async function submitLiarsDiceMove(body) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}

export function renderLiarsDiceBoard(state) {
  const board = $("board");
  board.classList.remove("bs-active");
  board.classList.add("ld-active"); // the bidding-track ring is wider than the default board grid; let it size freely
  board.innerHTML = "";
  $("column-controls").innerHTML = "";

  const over = Boolean(state.winner);
  const spectating = Boolean(session.game && session.game.isSpectator) || state.viewerSeat == null;
  const myTurn = !over && !spectating && state.currentPlayer === state.viewerSeat;

  if (over) setStatus(state.winner === state.viewerSeat ? "You win!" : `${state.winner} wins!`);
  else if (spectating) setStatus(`Spectating — ${state.currentPlayer} to bid`);
  else setStatus(myTurn ? "Your turn — raise or call Liar!" : `${state.currentPlayer}'s turn…`);

  // The bidding-track ring, with a red die on the current bid's space.
  board.appendChild(renderLdTrack(state));

  // Last challenge reveal: the one moment every cup comes up — show the bid, the true count, who lost, and all hands.
  if (state.lastReveal) {
    const r = state.lastReveal;
    const box = document.createElement("div");
    box.className = "ld-reveal";
    const summary = document.createElement("p");
    summary.className = "ld-reveal-summary";
    const lost = r.diceLost === 0 ? "no dice" : `${r.diceLost} ${r.diceLost === 1 ? "die" : "dice"}`;
    summary.textContent =
      `${r.challenger} called Liar on ${r.bidder}'s ${formatLdBid(r.bid)} — ` +
      `count was ${r.count}. ${r.loser} lost ${lost}.`;
    box.appendChild(summary);
    Object.keys(r.dice).sort().forEach((seat) => {
      const row = document.createElement("div");
      row.className = "ld-row";
      const label = document.createElement("span");
      label.className = "ld-seat";
      label.textContent = seat;
      row.appendChild(label);
      r.dice[seat].forEach((d) => row.appendChild(makeLdDie(d)));
      box.appendChild(row);
    });
    board.appendChild(box);
  }

  // Dice remaining per seat, highlighting whose turn it is.
  const table = document.createElement("table");
  table.className = "ld-counts";
  const header = table.insertRow();
  header.innerHTML = "<th>Player</th><th>Dice</th>";
  Object.keys(state.diceCounts).sort().forEach((seat) => {
    const row = table.insertRow();
    row.className = seat === state.currentPlayer && !over ? "ld-current" : "";
    const you = seat === state.viewerSeat ? " (you)" : "";
    const out = state.diceCounts[seat] === 0 ? " — out" : "";
    row.innerHTML = `<td>${seat}${you}${out}</td><td>${state.diceCounts[seat]}</td>`;
  });
  board.appendChild(table);

  // The viewer's own hidden hand.
  if (state.dice) {
    const hand = document.createElement("div");
    hand.className = "ld-row ld-hand";
    const label = document.createElement("span");
    label.className = "ld-seat";
    label.textContent = "Your dice:";
    hand.appendChild(label);
    state.dice.forEach((d) => hand.appendChild(makeLdDie(d)));
    board.appendChild(hand);
  }

  if (myTurn) board.appendChild(makeLdControls(state));
}

// The free-form bid input: a quantity, a face (2–6 or wild ones), a Bid button, and a Call Liar button. The server
// validates each raise against the bidding track and rejects an illegal one, surfaced as an error.
function makeLdControls(state) {
  const wrap = document.createElement("div");
  wrap.className = "ld-controls";

  const bidRow = document.createElement("div");
  bidRow.className = "row ld-bid-row";

  const qty = document.createElement("input");
  qty.type = "number";
  qty.min = "1";
  qty.value = state.currentBid ? String(state.currentBid.quantity) : "1";
  qty.className = "ld-qty";
  bidRow.appendChild(qty);

  const face = document.createElement("select");
  face.className = "ld-face";
  [2, 3, 4, 5, 6].forEach((f) => {
    const opt = document.createElement("option");
    opt.value = String(f);
    opt.textContent = `${f}s`;
    face.appendChild(opt);
  });
  const onesOpt = document.createElement("option");
  onesOpt.value = "ones";
  onesOpt.textContent = "ones (wild)";
  face.appendChild(onesOpt);
  if (state.currentBid && state.currentBid.face != null) face.value = String(state.currentBid.face);
  bidRow.appendChild(face);

  const bidBtn = document.createElement("button");
  bidBtn.textContent = "Bid";
  bidBtn.onclick = () => {
    const quantity = parseInt(qty.value, 10);
    if (!Number.isInteger(quantity) || quantity < 1) {
      flashError("game-error", "Enter a valid quantity.");
      return;
    }
    const f = face.value === "ones" ? null : parseInt(face.value, 10);
    submitLiarsDiceMove({ action: "bid", quantity, face: f });
  };
  bidRow.appendChild(bidBtn);
  wrap.appendChild(bidRow);

  const challenge = document.createElement("button");
  challenge.className = "ld-challenge";
  challenge.textContent = "Call Liar!";
  challenge.disabled = !state.currentBid; // the opening move of a round must be a bid
  challenge.onclick = () => submitLiarsDiceMove({ action: "challenge" });
  wrap.appendChild(challenge);

  return wrap;
}

GAMES.liarsdice.render = renderLiarsDiceBoard;
