"use strict";

/*
 * Minimal client for the Pekko game service. Plain JS, no build step, served same-origin with the API.
 *
 * Flow: sign in -> open one WebSocket -> create or join a lobby and subscribe to it -> the host starts the game.
 * Lobby subscribers are carried over to the game actor on start, so each player begins receiving GameStateUpdated
 * pushes automatically; this client never re-subscribes once a game begins. Moves are fired optimistically over REST
 * and the board is redrawn purely from the WebSocket pushes, with the server as the sole authority on legality.
 */

// The token is persisted to sessionStorage so a reload within the same tab stays signed in (and "My sessions" survives
// a refresh). sessionStorage is per-tab, so separate tabs/windows remain independent sessions.
const session = {
  token: null,
  me: null, // { id, name }
  ws: null,
  game: null // { roomId, gameType, isHost }
};

// Per-game-type knowledge the rest of the client stays agnostic to: display label, how a click becomes a move, and
// whether moves are made by picking a column (Connect Four) rather than an individual cell.
const GAMES = {
  tictactoe: { label: "Tic-Tac-Toe", maxPlayers: 2, move: (row, col) => ({ row, col }) },
  connectfour: { label: "Connect Four", maxPlayers: 2, move: (row, col) => ({ col }), columns: true }
};

const $ = (id) => document.getElementById(id);

// --- Auth seam -------------------------------------------------------------------------------------------------------
// login/register/playAsGuest are the only places that know how a token is obtained; each hands off to completeSignIn for
// everything downstream (persist, load profile, open the socket, route). Adding another auth method touches only these.
const TOKEN_KEY = "pekko-game-token";

function saveToken(token) {
  session.token = token;
  try {
    sessionStorage.setItem(TOKEN_KEY, token);
  } catch (_) {
    /* storage may be unavailable (private mode quirks); the in-memory token still works for this session */
  }
}

function clearToken() {
  session.token = null;
  try {
    sessionStorage.removeItem(TOKEN_KEY);
  } catch (_) {
    /* ignore */
  }
}

// Authenticate with existing credentials.
async function login(email, password) {
  const res = await api("/auth/token", { method: "POST", body: { email, password }, auth: false });
  if (!res.ok) throw new Error(res.data?.error || "Invalid email or password");
  await completeSignIn(res.data.token);
}

// Create a new account and sign in.
async function register(username, email, password) {
  const res = await api("/auth/register", { method: "POST", body: { username, email, password }, auth: false });
  if (!res.ok) throw new Error(res.data?.error || `Registration failed (${res.status})`);
  await completeSignIn(res.data.token);
}

// Guest play: register a throwaway account behind the scenes so the user only types a display name.
async function playAsGuest(name) {
  const suffix = (crypto.randomUUID && crypto.randomUUID()) || String(Date.now()) + Math.random();
  const body = { username: name, email: `guest-${suffix}@example.invalid`, password: `pw-${suffix}` };
  const res = await api("/auth/register", { method: "POST", body, auth: false });
  if (!res.ok) throw new Error(res.data?.error || "Could not start a guest session");
  await completeSignIn(res.data.token);
}

// Persist a freshly-obtained token, then bring the session up and route into the app.
async function completeSignIn(token) {
  saveToken(token);
  if (!(await establishSession())) throw new Error("Could not load your profile — please try again.");
  routeAfterSignIn();
}

// Load the profile for the current token and open the WebSocket. Returns false (and clears the token) if the token is
// rejected — shared by a fresh sign-in and by restoring a saved session.
async function establishSession() {
  const who = await api("/auth/whoami");
  if (!who.ok) {
    clearToken();
    return false;
  }
  session.me = who.data;
  await connectWs();
  $("whoami").textContent = `Signed in as ${session.me.name}`;
  $("logout").classList.remove("hidden");
  return true;
}

// Restore a session saved in sessionStorage (survives a reload within the tab). Returns true if it routed into the app.
async function restoreSession() {
  let token = null;
  try {
    token = sessionStorage.getItem(TOKEN_KEY);
  } catch (_) {
    /* ignore */
  }
  if (!token) return false;
  session.token = token;
  if (!(await establishSession())) return false; // expired/invalid — fall back to the login screen
  routeAfterSignIn();
  return true;
}

// Land on the invite-link target if one is pending, otherwise the lobby.
function routeAfterSignIn() {
  if (pendingJoinRoomId) {
    const id = pendingJoinRoomId;
    pendingJoinRoomId = null;
    joinByRoomId(id);
  } else {
    enterLobby();
  }
}

// Clear the session (token, profile, socket) and return to the login screen.
function logout() {
  clearToken();
  session.me = null;
  session.game = null;
  if (session.ws) {
    try {
      session.ws.close();
    } catch (_) {
      /* ignore */
    }
    session.ws = null;
  }
  $("whoami").textContent = "";
  $("logout").classList.add("hidden");
  showPanel("login");
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
  if (!res.ok) return flashError("lobby-error", res.data?.error || res.data || "Could not create game");
  await enterGame({ roomId: res.data.roomId, gameType, isHost: true });
}

async function joinGame(roomId, gameType) {
  setError("lobby-error", "");
  const res = await api(`/lobby/${roomId}/join`, { method: "POST", body: {} });
  if (!res.ok) return flashError("lobby-error", res.data?.error || res.data || "Could not join game");
  await enterGame({ roomId, gameType, isHost: false });
}

async function refreshLobbies() {
  setError("lobby-error", "");
  // The server filters by game type when a `gameType` query param is supplied; empty means all types.
  const filter = $("lobby-filter").value;
  const res = await api(`/lobby/list${filter ? `?gameType=${filter}` : ""}`);
  const list = $("lobby-list");
  list.innerHTML = "";
  if (!res.ok) return flashError("lobby-error", "Could not list lobbies");

  const lobbies = (res.data.lobbies || [])
    .filter((l) => GAMES[l.gameType.toLowerCase()])
    .filter((l) => !(session.me && l.players[session.me.id])); // lobbies you're in appear under "Your games & lobbies"
  if (lobbies.length === 0) {
    const li = document.createElement("li");
    li.className = "meta";
    li.textContent = filter ? "No open lobbies for this game. Create one above." : "No open lobbies. Create one above.";
    list.appendChild(li);
    return;
  }

  for (const lobby of lobbies) {
    const type = lobby.gameType.toLowerCase();
    const max = GAMES[type].maxPlayers;
    const count = Object.keys(lobby.players).length;
    const host = lobby.players[lobby.hostId]; // the host is always present in the players map
    const hostName = host ? host.name : "unknown";
    const li = document.createElement("li");

    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = `${GAMES[type].label} — hosted by ${hostName} — ${count}/${max} players`;

    const full = count >= max;
    const join = document.createElement("button");
    join.textContent = full ? "Full" : "Join";
    join.disabled = full;
    if (!full) join.onclick = () => joinGame(lobby.roomId, type);

    li.append(meta, join);
    list.appendChild(li);
  }
}

// Reset the game view to a clean slate for a given game/lobby and show it. The caller sets the status and subscribes.
function prepareGameView({ roomId, gameType, isHost }) {
  session.game = { roomId, gameType, isHost };
  $("game-title").textContent = GAMES[gameType].label;
  $("board").innerHTML = "";
  $("column-controls").innerHTML = "";
  setError("game-error", "");
  $("start-game").classList.add("hidden");
  $("start-game").disabled = true;
  $("post-game-bar").classList.add("hidden");
  $("rematch-btn").classList.add("hidden");
  clearTimeout(copyLinkTimer);
  $("copy-link").textContent = COPY_LINK_LABEL;
  $("chat-log").innerHTML = "";
  showPanel("game");
}

// Show the lobby screen and refresh both lists: the player's own sessions and the open lobbies.
function enterLobby() {
  showPanel("lobby");
  refreshMySessions();
  refreshLobbies();
}

// Load the player's current participation (joined lobbies + in-progress games) from live actor state and render
// Return entries. Best-effort: the open-lobby list remains the primary view if this fails.
async function refreshMySessions() {
  const res = await api("/players/me/sessions");
  if (res.ok) renderMySessions(res.data);
}

function renderMySessions(data) {
  const ul = $("my-sessions");
  ul.innerHTML = "";
  const games = (data.games || []).filter((g) => GAMES[g.gameType.toLowerCase()]);
  const lobbies = (data.lobbies || []).filter((l) => GAMES[l.gameType.toLowerCase()]);

  if (games.length === 0 && lobbies.length === 0) {
    ul.appendChild(sessionRow("You're not in any games or lobbies right now.", null, null));
    return;
  }

  // In-progress games first (more actionable), then pre-game lobbies.
  for (const g of games) {
    const type = g.gameType.toLowerCase();
    ul.appendChild(
      sessionRow(`${GAMES[type].label} — in progress`, "Return", () => resumeGame({ roomId: g.roomId, gameType: type }))
    );
  }
  for (const l of lobbies) {
    const type = l.gameType.toLowerCase();
    const youHost = Boolean(session.me) && l.hostId === session.me.id;
    const count = Object.keys(l.players).length;
    const phase = l.status === "Finished" ? "finished — chat/rematch" : `lobby (${count}/${GAMES[type].maxPlayers})`;
    const label = `${GAMES[type].label} — ${phase}${youHost ? " · you host" : ""}`;
    ul.appendChild(sessionRow(label, "Return", () => enterGame({ roomId: l.roomId, gameType: type, isHost: youHost })));
  }
}

// Build one row for the sessions list: a label and, when an action is given, a button.
function sessionRow(text, btnText, onClick) {
  const li = document.createElement("li");
  const meta = document.createElement("span");
  meta.className = "meta";
  meta.textContent = text;
  li.appendChild(meta);
  if (btnText) {
    const btn = document.createElement("button");
    btn.textContent = btnText;
    btn.onclick = onClick;
    li.appendChild(btn);
  }
  return li;
}

// Enter the game view for a lobby we just created or joined, then subscribe to the lobby so we receive its push events.
async function enterGame({ roomId, gameType, isHost }) {
  prepareGameView({ roomId, gameType, isHost });
  setStatus(isHost ? "Waiting for an opponent to join…" : "Joined. Waiting for the host to start…");
  const res = await api(`/lobby/${roomId}/subscribe`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not subscribe to the lobby");
  loadChatHistory(roomId, gameType);
}

// Return to a game already in progress (from the sessions list): subscribe to the game actor, which immediately pushes
// the current board. Pre-game lobbies are returned to via enterGame instead (they subscribe to the lobby).
async function resumeGame({ roomId, gameType }) {
  prepareGameView({ roomId, gameType, isHost: false });
  setStatus("Returning to the game…");
  const res = await api(`/${gameType}/${roomId}/subscribe`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not return to the game");
  loadChatHistory(roomId, gameType);
}

// A pre-game lobby changed: refresh the player count, surface the host, and (for the host) enable Start when ready.
function onLobbyUpdated(metadata) {
  if (!session.game || metadata.roomId !== session.game.roomId) return;
  if (metadata.status === "InProgress") {
    $("start-game").classList.add("hidden"); // the game is live; Start no longer applies
    $("post-game-bar").classList.add("hidden"); // a rematch just began; the next GameStateUpdated takes over
    $("rematch-btn").classList.add("hidden");
    return; // game state pushes take over from here
  }
  if (metadata.status === "Finished") {
    onMatchFinished(metadata);
    return;
  }

  const count = Object.keys(metadata.players).length;
  const host = metadata.players[metadata.hostId];
  const hostName = host ? host.name : "the host";
  // The host role can migrate if the original host leaves a pre-game lobby, so re-derive it on every update.
  const youHost = Boolean(session.me) && metadata.hostId === session.me.id;
  session.game.isHost = youHost;

  const max = GAMES[session.game.gameType].maxPlayers;
  if (youHost) {
    const ready = metadata.status === "ReadyToStart";
    $("start-game").classList.remove("hidden");
    $("start-game").disabled = !ready;
    setStatus(ready ? "Opponent joined — press Start." : `You're hosting — waiting for an opponent… (${count}/${max})`);
  } else {
    $("start-game").classList.add("hidden");
    setStatus(`Hosted by ${hostName} — waiting for them to start… (${count}/${max})`);
  }
}

// The room's match has ended but the room survives: keep the final board on screen, surface a rematch bar, and let
// the host start again. The guest sees a wait message; both keep chatting via the still-live ChatMessage stream.
function onMatchFinished(metadata) {
  const youHost = Boolean(session.me) && metadata.hostId === session.me.id;
  session.game.isHost = youHost;

  const series = metadata.matchCount > 1 ? ` (match ${metadata.matchCount})` : "";
  $("post-game-status").textContent = youHost
    ? `Game over${series}. Start a rematch when you're ready.`
    : `Game over${series}. Waiting for the host to start a rematch…`;
  $("rematch-btn").classList.toggle("hidden", !youHost);
  $("post-game-bar").classList.remove("hidden");
}

async function rematch() {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.roomId}/start`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not start the rematch");
  // On success a fresh GameStateUpdated arrives over the WebSocket and clears the post-game bar.
}

async function startGame() {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.roomId}/start`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not start the game");
  // On success the game-state push arrives over the WebSocket and renders the board.
}

function onGameEnded(result) {
  if (result === "Cancelled") setStatus("Game cancelled.");
  // A "Completed" end is already reflected by the final GameStateUpdated (winner/draw), so leave that status in place.
}

async function leaveGame() {
  const game = session.game;
  if (game) await api(`/lobby/${game.roomId}/leave`, { method: "POST", body: {} });
  session.game = null;
  enterLobby();
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
async function loadChatHistory(roomId, gameType) {
  const res = await api(`/${gameType}/${roomId}/chat`);
  if (res.ok && res.data && Array.isArray(res.data.messages)) res.data.messages.forEach(appendChat);
}

// Send a chat frame over the WebSocket. Requires an open socket and an active game/lobby context.
function sendChat(text) {
  if (!session.ws || session.ws.readyState !== WebSocket.OPEN || !session.game) return;
  session.ws.send(JSON.stringify({ type: "ChatSend", roomId: session.game.roomId, text }));
}

// --- Board rendering -------------------------------------------------------------------------------------------------
// Renders any grid game-state view: board is rows of cell tokens (mark symbol or "" when empty). Clicks are optimistic —
// the server validates turn/legality and rejects with a plain-text message we surface. Column-based games (Connect Four)
// are played via drop buttons above the board rather than by clicking individual cells.
function renderBoard(state) {
  const board = $("board");
  $("start-game").classList.add("hidden"); // a live board means the game has started; Start no longer applies
  $("post-game-bar").classList.add("hidden"); // a fresh board (e.g. a rematch) supersedes the prior match's post-game bar
  $("rematch-btn").classList.add("hidden");
  setError("game-error", ""); // the state changed, so any prior move error (e.g. "not your turn") is now stale
  const rows = state.board;
  const cols = rows[0] ? rows[0].length : 0;
  board.style.setProperty("--cols", cols);
  board.innerHTML = "";

  const over = Boolean(state.winner) || state.draw === true;
  const columnMode = Boolean(session.game && GAMES[session.game.gameType] && GAMES[session.game.gameType].columns);

  renderColumnControls(columnMode ? cols : 0, rows, over);

  rows.forEach((cells, r) => {
    cells.forEach((mark, c) => {
      const cell = document.createElement("div");
      // In column mode the cells are display-only; you drop via the buttons above the board.
      const clickable = !over && !columnMode;
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
// cell-click games. A column whose top cell is filled is full, so its button is disabled.
function renderColumnControls(cols, rows, over) {
  const controls = $("column-controls");
  controls.innerHTML = "";
  if (cols === 0) return;
  controls.style.setProperty("--cols", cols);
  for (let c = 0; c < cols; c++) {
    const btn = document.createElement("button");
    btn.className = "col-btn";
    btn.textContent = "▼";
    btn.disabled = over || (rows[0] && rows[0][c] !== ""); // column full when its top cell is occupied
    btn.onclick = () => submitMove(0, c); // row is ignored for column moves
    controls.appendChild(btn);
  }
}

async function submitMove(row, col) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const payload = GAMES[game.gameType].move(row, col);
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body: payload });
  // Successful moves redraw via the WebSocket push; only failures need surfacing here.
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}

// --- Deep links ------------------------------------------------------------------------------------------------------
// A shareable URL hash (#join/<roomId>) drops a visitor straight into a specific lobby. The hash keeps the request on
// the static-served `/` route (a path like /lobby/<id> is a JSON API route). It is captured once at load and consumed
// after the visitor signs in, since joining needs a token and an open WebSocket.
// Parse a #join/<roomId> invite hash, or null. Captured at load and also watched live via hashchange below.
function parseJoinHash() {
  return (location.hash.match(/^#join\/([0-9a-fA-F-]+)$/) || [])[1] || null;
}

// A pending invite to consume once the visitor is signed in. Set from the initial hash and from later hash changes.
let pendingJoinRoomId = parseJoinHash();
if (pendingJoinRoomId) history.replaceState(null, "", location.pathname + location.search);

// Following an invite link while the page is already open changes only the hash (no reload). If we're already signed
// in, join right away; otherwise hold it until sign-in. A link opened in a fresh tab is a full load, which — with the
// in-memory-only token — always starts at sign-in.
window.addEventListener("hashchange", () => {
  const id = parseJoinHash();
  if (!id) return;
  history.replaceState(null, "", location.pathname + location.search);
  if (session.me) joinByRoomId(id);
  else pendingJoinRoomId = id;
});

// Join the lobby named by a deep link: look up its game type, then join — falling back to the lobby list on any issue.
async function joinByRoomId(roomId) {
  enterLobby();
  const res = await api(`/lobby/${roomId}`);
  if (!res.ok) return flashError("lobby-error", "That lobby is no longer available.");
  const type = res.data.gameType.toLowerCase();
  if (!GAMES[type]) return flashError("lobby-error", "That game isn't supported in the web UI yet.");
  joinGame(roomId, type);
}

// Copy a shareable invite link for the current lobby/game to the clipboard, with a prompt() fallback. Briefly flips the
// button label to confirm; uses a fixed label and a single tracked timer so a quick re-click or game switch can't leave
// it stuck on "Link copied!".
const COPY_LINK_LABEL = "Copy invite link";
let copyLinkTimer;
async function copyInviteLink() {
  if (!session.game) return;
  const link = `${location.origin}/#join/${session.game.roomId}`;
  const btn = $("copy-link");
  try {
    await navigator.clipboard.writeText(link);
    btn.textContent = "Link copied!";
    clearTimeout(copyLinkTimer);
    copyLinkTimer = setTimeout(() => (btn.textContent = COPY_LINK_LABEL), 1500);
  } catch (_) {
    window.prompt("Copy this invite link:", link);
  }
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
  clearTimeout(errorTimers[id]); // cancel any pending auto-dismiss; an explicit set/clear wins
}

// Show a transient error that auto-dismisses after a few seconds, so a rejected action (e.g. an illegal move that
// triggers no state update to clear it) doesn't leave a message stranded on screen.
const errorTimers = {};
function flashError(id, text) {
  setError(id, text);
  if (text) errorTimers[id] = setTimeout(() => setError(id, ""), 5000);
}

// --- Wiring ----------------------------------------------------------------------------------------------------------

// Toggle between the login and register forms on the sign-in screen.
function showAuthMode(mode) {
  const register = mode === "register";
  $("login-form").classList.toggle("hidden", register);
  $("to-register-line").classList.toggle("hidden", register);
  $("register-form").classList.toggle("hidden", !register);
  $("to-login-line").classList.toggle("hidden", !register);
  setError("login-error", "");
}

// Shared submit wrapper for the auth forms: runs the sign-in action with the button disabled and surfaces any error.
function wireAuthForm(formId, signIn) {
  $(formId).addEventListener("submit", async (e) => {
    e.preventDefault();
    setError("login-error", "");
    const button = e.target.querySelector("button");
    button.disabled = true;
    try {
      await signIn();
    } catch (err) {
      setError("login-error", err.message || "Sign-in failed");
    } finally {
      button.disabled = false;
    }
  });
}

wireAuthForm("login-form", () => login($("login-email").value.trim(), $("login-password").value));
wireAuthForm("register-form", () =>
  register($("register-username").value.trim(), $("register-email").value.trim(), $("register-password").value)
);
wireAuthForm("guest-form", () => playAsGuest($("guest-name").value.trim()));

$("to-register").addEventListener("click", (e) => {
  e.preventDefault();
  showAuthMode("register");
});
$("to-login").addEventListener("click", (e) => {
  e.preventDefault();
  showAuthMode("login");
});
$("logout").addEventListener("click", logout);

for (const button of document.querySelectorAll("[data-gametype]")) {
  button.addEventListener("click", () => createGame(button.dataset.gametype));
}

$("refresh-lobbies").addEventListener("click", () => {
  refreshMySessions();
  refreshLobbies();
});
$("lobby-filter").addEventListener("change", refreshLobbies);
$("start-game").addEventListener("click", startGame);
$("rematch-btn").addEventListener("click", rematch);
$("back-to-lobby").addEventListener("click", enterLobby); // browse the lobby without leaving the current game
$("leave-game").addEventListener("click", leaveGame);
$("copy-link").addEventListener("click", copyInviteLink);

$("chat-form").addEventListener("submit", (e) => {
  e.preventDefault();
  const input = $("chat-input");
  const text = input.value.trim();
  if (!text) return;
  sendChat(text);
  input.value = "";
});

// On load, restore a saved session if there is one; otherwise the login screen (shown by default) stays.
restoreSession().catch(() => showPanel("login"));
