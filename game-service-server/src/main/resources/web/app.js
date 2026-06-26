"use strict";

/*
 * Minimal client for the Pekko game service. Plain JS, no build step, served same-origin with the API.
 *
 * Flow: authenticate() -> open one WebSocket -> create or join a lobby and subscribe to it -> the host starts the game.
 * Lobby subscribers are carried over to the game actor on start, so each player begins receiving GameStateUpdated
 * pushes automatically; this client never re-subscribes once a game begins. Moves are fired optimistically over REST
 * and the board is redrawn purely from the WebSocket pushes, with the server as the sole authority on legality.
 */

// In-memory session state only; nothing is persisted to localStorage, so a refresh is a fresh session.
const session = {
  token: null,
  me: null, // { id, name }
  ws: null,
  game: null // { gameId, gameType, isHost }
};

// Per-game-type knowledge the rest of the client stays agnostic to: display label and how a clicked cell becomes a move.
const GAMES = {
  tictactoe: { label: "Tic-Tac-Toe", move: (row, col) => ({ row, col }) },
  connectfour: { label: "Connect Four", move: (row, col) => ({ col }) }
};

const $ = (id) => document.getElementById(id);

// --- Auth seam -------------------------------------------------------------------------------------------------------
// authenticate() is the single point that knows how a token is obtained. Today it registers a throwaway account so the
// user only types a name; swapping in real email/password login later changes only this function and the login markup.
async function authenticate(name) {
  const suffix = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random();
  const body = {
    username: name,
    email: `guest-${suffix}@example.invalid`,
    password: `pw-${suffix}` // satisfies the 8–128 char rule; throwaway, never shown to the user
  };
  const res = await api("/auth/register", { method: "POST", body, auth: false });
  if (!res.ok) throw new Error(res.data?.error || `Registration failed (${res.status})`);
  session.token = res.data.token;

  const who = await api("/auth/whoami");
  if (!who.ok) throw new Error("Could not load profile");
  session.me = who.data;
}

// --- HTTP helper -----------------------------------------------------------------------------------------------------
// Returns { ok, status, data } where data is parsed JSON when the response is JSON, otherwise the raw text (the API
// returns plain-text bodies for move rejections and other errors).
async function api(path, opts = {}) {
  const { method = "GET", body, auth = true } = opts;
  const headers = {};
  if (body !== undefined) headers["Content-Type"] = "application/json";
  if (auth && session.token) headers["Authorization"] = `Bearer ${session.token}`;

  const res = await fetch(path, { method, headers, body: body !== undefined ? JSON.stringify(body) : undefined });
  const text = await res.text();
  const isJson = (res.headers.get("content-type") || "").includes("application/json");
  let data = text;
  if (isJson && text) {
    try {
      data = JSON.parse(text);
    } catch (_) {
      data = text;
    }
  }
  return { ok: res.ok, status: res.status, data };
}

// --- WebSocket -------------------------------------------------------------------------------------------------------
// One socket per player. The browser WebSocket API cannot set headers, so the JWT travels as the access_token query
// parameter (the server accepts it there for /ws only). Resolves once the socket is open so subscribe calls — which
// require a registered PlayerActor — never race ahead of the connection.
function connectWs() {
  return new Promise((resolve, reject) => {
    const proto = location.protocol === "https:" ? "wss" : "ws";
    const ws = new WebSocket(`${proto}://${location.host}/ws?access_token=${encodeURIComponent(session.token)}`);
    session.ws = ws;
    ws.onopen = () => resolve();
    ws.onerror = (e) => reject(e);
    ws.onclose = () => {
      session.ws = null;
    };
    ws.onmessage = (ev) => {
      let msg;
      try {
        msg = JSON.parse(ev.data);
      } catch (_) {
        return;
      }
      handleEvent(msg);
    };
  });
}

// Dispatch a server push by its tagged type. Only events relevant to the single active game/lobby are acted on.
function handleEvent(msg) {
  switch (msg.type) {
    case "LobbyUpdated":
      onLobbyUpdated(msg.metadata);
      break;
    case "GameStateUpdated":
      renderBoard(msg.state);
      break;
    case "GameEnded":
      onGameEnded(msg.result);
      break;
    case "ChatMessage":
      appendChat(msg);
      break;
    default:
      break;
  }
}

// --- Lobby -----------------------------------------------------------------------------------------------------------
async function createGame(gameType) {
  setError("lobby-error", "");
  const res = await api(`/lobby/create/${gameType}`, { method: "POST", body: {} });
  if (!res.ok) return setError("lobby-error", res.data?.error || res.data || "Could not create game");
  await enterGame({ gameId: res.data.gameId, gameType, isHost: true });
}

async function joinGame(gameId, gameType) {
  setError("lobby-error", "");
  const res = await api(`/lobby/${gameId}/join`, { method: "POST", body: {} });
  if (!res.ok) return setError("lobby-error", res.data?.error || res.data || "Could not join game");
  await enterGame({ gameId, gameType, isHost: false });
}

async function refreshLobbies() {
  setError("lobby-error", "");
  // The server filters by game type when a `gameType` query param is supplied; empty means all types.
  const filter = $("lobby-filter").value;
  const res = await api(`/lobby/list${filter ? `?gameType=${filter}` : ""}`);
  const list = $("lobby-list");
  list.innerHTML = "";
  if (!res.ok) return setError("lobby-error", "Could not list lobbies");

  const lobbies = (res.data.lobbies || []).filter((l) => GAMES[l.gameType.toLowerCase()]);
  if (lobbies.length === 0) {
    const li = document.createElement("li");
    li.className = "meta";
    li.textContent = filter ? "No open lobbies for this game. Create one above." : "No open lobbies. Create one above.";
    list.appendChild(li);
    return;
  }

  for (const lobby of lobbies) {
    const type = lobby.gameType.toLowerCase();
    const count = Object.keys(lobby.players).length;
    const host = lobby.players[lobby.hostId]; // the host is always present in the players map
    const hostName = host ? host.name : "unknown";
    const mine = session.me && lobby.hostId === session.me.id;
    const li = document.createElement("li");

    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent =
      `${GAMES[type].label} — hosted by ${hostName}${mine ? " (you)" : ""} — ${count}/2 players`;

    const join = document.createElement("button");
    join.textContent = "Join";
    join.onclick = () => joinGame(lobby.gameId, type);

    li.append(meta, join);
    list.appendChild(li);
  }
}

// Enter the game view for a lobby we just created or joined, then subscribe so we receive its push events.
async function enterGame({ gameId, gameType, isHost }) {
  session.game = { gameId, gameType, isHost };
  $("game-title").textContent = GAMES[gameType].label;
  $("board").innerHTML = "";
  setError("game-error", "");
  $("start-game").classList.add("hidden");
  $("start-game").disabled = true;
  setStatus(isHost ? "Waiting for an opponent to join…" : "Joined. Waiting for the host to start…");
  $("chat-log").innerHTML = "";
  showPanel("game");

  const res = await api(`/lobby/${gameId}/subscribe`, { method: "POST", body: {} });
  if (!res.ok) setError("game-error", res.data?.error || res.data || "Could not subscribe to the lobby");
  loadChatHistory(gameId, gameType);
}

// A pre-game lobby changed: refresh the player count, surface the host, and (for the host) enable Start when ready.
function onLobbyUpdated(metadata) {
  if (!session.game || metadata.gameId !== session.game.gameId) return;
  if (metadata.status === "InProgress") return; // game state pushes take over from here

  const count = Object.keys(metadata.players).length;
  const host = metadata.players[metadata.hostId];
  const hostName = host ? host.name : "the host";
  // The host role can migrate if the original host leaves a pre-game lobby, so re-derive it on every update.
  const youHost = Boolean(session.me) && metadata.hostId === session.me.id;
  session.game.isHost = youHost;

  if (youHost) {
    const ready = metadata.status === "ReadyToStart";
    $("start-game").classList.remove("hidden");
    $("start-game").disabled = !ready;
    setStatus(ready ? "Opponent joined — press Start." : `You're hosting — waiting for an opponent… (${count}/2)`);
  } else {
    $("start-game").classList.add("hidden");
    setStatus(`Hosted by ${hostName} — waiting for them to start… (${count}/2)`);
  }
}

async function startGame() {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.gameId}/start`, { method: "POST", body: {} });
  if (!res.ok) setError("game-error", res.data?.error || res.data || "Could not start the game");
  // On success the game-state push arrives over the WebSocket and renders the board.
}

function onGameEnded(result) {
  if (result === "Cancelled") setStatus("Game cancelled.");
  // A "Completed" end is already reflected by the final GameStateUpdated (winner/draw), so leave that status in place.
}

async function leaveGame() {
  const game = session.game;
  if (game) await api(`/lobby/${game.gameId}/leave`, { method: "POST", body: {} });
  session.game = null;
  showPanel("lobby");
  refreshLobbies();
}

// --- Chat ------------------------------------------------------------------------------------------------------------
// Chat rides the same one-per-player WebSocket. Outbound messages are ClientMessage.ChatSend frames; the server fans
// the resulting ChatMessage back to every subscriber (including the sender), so — like the board — we render purely
// from received events rather than echoing locally.
function appendChat(m) {
  const log = $("chat-log");
  const li = document.createElement("li");
  li.className = "chat-msg" + (session.me && m.senderId === session.me.id ? " own" : "");

  const who = document.createElement("span");
  who.className = "chat-who";
  who.textContent = m.senderName + ":";

  const text = document.createElement("span");
  text.className = "chat-text";
  text.textContent = m.text; // textContent, never innerHTML, so message bodies can't inject markup

  li.append(who, text);
  log.appendChild(li);
  log.scrollTop = log.scrollHeight; // keep the latest message in view
}

// Load the recent backscroll (oldest first) for a game/lobby; best-effort, so an empty or failed load is harmless.
async function loadChatHistory(gameId, gameType) {
  const res = await api(`/${gameType}/${gameId}/chat`);
  if (res.ok && res.data && Array.isArray(res.data.messages)) res.data.messages.forEach(appendChat);
}

// Send a chat frame over the WebSocket. Requires an open socket and an active game/lobby context.
function sendChat(text) {
  if (!session.ws || session.ws.readyState !== WebSocket.OPEN || !session.game) return;
  session.ws.send(JSON.stringify({ type: "ChatSend", gameId: session.game.gameId, text }));
}

// --- Board rendering -------------------------------------------------------------------------------------------------
// Renders any grid game-state view: board is rows of cell tokens (mark symbol or "" when empty). Clicks are optimistic —
// the server validates turn/legality and rejects with a plain-text message we surface.
function renderBoard(state) {
  const board = $("board");
  const rows = state.board;
  const cols = rows[0] ? rows[0].length : 0;
  board.style.setProperty("--cols", cols);
  board.innerHTML = "";

  const over = Boolean(state.winner) || state.draw === true;

  rows.forEach((cells, r) => {
    cells.forEach((mark, c) => {
      const cell = document.createElement("div");
      cell.className = "cell" + (mark ? ` mark-${mark}` : "") + (over ? " disabled" : " clickable");
      cell.textContent = mark;
      if (!over) cell.onclick = () => submitMove(r, c);
      board.appendChild(cell);
    });
  });

  if (state.winner) setStatus(`${state.winner} wins!`);
  else if (state.draw) setStatus("Draw.");
  else setStatus(`Turn: ${state.currentPlayer}`);
}

async function submitMove(row, col) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const payload = GAMES[game.gameType].move(row, col);
  const res = await api(`/${game.gameType}/${game.gameId}/move`, { method: "POST", body: payload });
  // Successful moves redraw via the WebSocket push; only failures need surfacing here.
  if (!res.ok) setError("game-error", res.data?.error || res.data || "Move rejected");
}

// --- View helpers ----------------------------------------------------------------------------------------------------
function showPanel(name) {
  for (const id of ["login", "lobby", "game"]) $(id).classList.toggle("hidden", id !== name);
}

function setStatus(text) {
  $("game-status").textContent = text;
}

function setError(id, text) {
  $(id).textContent = text;
}

// --- Wiring ----------------------------------------------------------------------------------------------------------
$("login-form").addEventListener("submit", async (e) => {
  e.preventDefault();
  setError("login-error", "");
  const name = $("name").value.trim();
  if (!name) return;
  const button = e.target.querySelector("button");
  button.disabled = true;
  try {
    await authenticate(name);
    await connectWs();
    $("whoami").textContent = `Signed in as ${session.me.name}`;
    showPanel("lobby");
    refreshLobbies();
  } catch (err) {
    setError("login-error", err.message || "Sign-in failed");
  } finally {
    button.disabled = false;
  }
});

for (const button of document.querySelectorAll("[data-gametype]")) {
  button.addEventListener("click", () => createGame(button.dataset.gametype));
}

$("refresh-lobbies").addEventListener("click", refreshLobbies);
$("lobby-filter").addEventListener("change", refreshLobbies);
$("start-game").addEventListener("click", startGame);
$("leave-game").addEventListener("click", leaveGame);

$("chat-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const input = $("chat-input");
  const text = input.value.trim();
  if (!text) return;
  sendChat(text);
  input.value = "";
});
