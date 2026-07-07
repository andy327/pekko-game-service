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
  traceWs: null,
  game: null // { roomId, gameType, isHost }
};

// Per-game-type knowledge the rest of the client stays agnostic to: display label, how a click becomes a move, and
// whether moves are made by picking a column (Connect Four) rather than an individual cell.
const GAMES = {
  tictactoe:   { label: "Tic-Tac-Toe", maxPlayers: 2, move: (row, col) => ({ row, col }) },
  connectfour: { label: "Connect Four", maxPlayers: 2, move: (row, col) => ({ col }), columns: true },
  battleship:  { label: "Battleship",   maxPlayers: 2, move: (row, col) => ({ row, col }) },
  pig:         { label: "Pig",          maxPlayers: 8, pig: true },
  mastermind:  { label: "Mastermind",   maxPlayers: 2, mastermind: true },
  liarsdice:   { label: "Liar's Dice",  maxPlayers: 6, liarsdice: true },
  texasholdem: { label: "Texas Hold 'Em", maxPlayers: 6, texasholdem: true }
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
  $("debug-nav").classList.remove("hidden");
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
  disconnectTraceWs();
  $("whoami").textContent = "";
  $("logout").classList.add("hidden");
  $("debug-nav").classList.add("hidden");
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

  // entries are { metadata, spectatorCount }; the server already orders them most-watched-first
  const entries = (res.data.lobbies || [])
    .filter((e) => GAMES[e.metadata.gameType.toLowerCase()])
    .filter((e) => !(session.me && e.metadata.players[session.me.id])); // yours appear under "Your games & lobbies"
  if (entries.length === 0) {
    const li = document.createElement("li");
    li.className = "meta";
    li.textContent = filter ? "No open lobbies for this game. Create one above." : "No open lobbies. Create one above.";
    list.appendChild(li);
    return;
  }

  for (const entry of entries) {
    const lobby = entry.metadata;
    const type = lobby.gameType.toLowerCase();
    const max = GAMES[type].maxPlayers;
    const count = Object.keys(lobby.players).length;
    const host = lobby.players[lobby.hostId]; // the host is always present in the players map
    const hostName = host ? host.name : "unknown";
    const joinable = lobby.status === "WaitingForPlayers" || lobby.status === "ReadyToStart";
    const phase = lobby.status === "InProgress" ? "in progress" : lobby.status === "Finished" ? "post-game" : null;
    const li = document.createElement("li");

    const watching = entry.spectatorCount > 0 ? ` — ${entry.spectatorCount} watching` : "";
    const meta = document.createElement("span");
    meta.className = "meta";
    meta.textContent = phase
      ? `${GAMES[type].label} — hosted by ${hostName} — ${phase}${watching}`
      : `${GAMES[type].label} — hosted by ${hostName} — ${count}/${max} players${watching}`;

    li.appendChild(meta);

    // grouped so Join (when present) and Spectate always sit together at a consistent spot, rather than each being
    // spread out individually by the row's space-between; Spectate first, Join last so Join always lands at the
    // rightmost edge
    const actions = document.createElement("span");
    actions.className = "actions";

    const spectate = document.createElement("button");
    spectate.textContent = "Spectate";
    spectate.onclick = () => spectateGame(lobby.roomId, type, lobby.status);
    actions.appendChild(spectate);

    if (joinable) {
      const full = count >= max;
      const join = document.createElement("button");
      join.textContent = full ? "Full" : "Join";
      join.disabled = full;
      if (!full) join.onclick = () => joinGame(lobby.roomId, type);
      actions.appendChild(join);
    }

    li.appendChild(actions);

    list.appendChild(li);
  }
}

// Reset the game view to a clean slate for a given game/lobby and show it. The caller sets the status and subscribes.
// isSpectator marks a read-only viewer (board clicks disabled, no chat send); isLive tracks whether we're watching an
// already-started match (game-actor subscription) vs. a pre-game lobby (lobby subscription) — needed so leaveGame
// calls the matching unsubscribe endpoint.
function prepareGameView({ roomId, gameType, isHost, isSpectator = false, isLive = false }) {
  session.game = { roomId, gameType, isHost, isSpectator, isLive };
  holdemPreAction = null; // drop any armed Hold 'Em pre-action from a previous game
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
  $("join-as-player").classList.add("hidden"); // shown only once onLobbyUpdated confirms a joinable, open seat
  applySpectatorUi(isSpectator);
  showPanel("game");
}

// Toggle the read-only affordances that depend on whether we're spectating: a spectator gets no chat-send box and a
// "Stop spectating" label instead of "Leave" (both call the same leaveGame, which routes to the right unsubscribe).
function applySpectatorUi(isSpectator) {
  $("chat-form").classList.toggle("hidden", isSpectator);
  $("leave-game").textContent = isSpectator ? "Stop spectating" : "Leave";
}

// Watch a lobby or live game without taking a seat. Subscribes via the lobby endpoint for a pre-game room (the same
// path a joining player uses) or the game endpoint for an already-started match; both endpoints accept any
// authenticated player regardless of whether they're seated.
async function spectateGame(roomId, gameType, status) {
  setError("lobby-error", "");
  const live = status === "InProgress";
  prepareGameView({ roomId, gameType, isHost: false, isSpectator: true, isLive: live });
  setStatus(live ? "Spectating the match…" : "Spectating the lobby…");
  const path = live ? `/${gameType}/${roomId}/subscribe` : `/lobby/${roomId}/subscribe`;
  const res = await api(path, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not spectate");
  loadChatHistory(roomId, gameType);
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
  prepareGameView({ roomId, gameType, isHost: false, isLive: true });
  setStatus("Returning to the game…");
  const res = await api(`/${gameType}/${roomId}/subscribe`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not return to the game");
  loadChatHistory(roomId, gameType);
}

// A pre-game lobby changed: refresh the player count, surface the host, and (for the host) enable Start when ready.
function onLobbyUpdated(metadata) {
  if (!session.game || metadata.roomId !== session.game.roomId) return;

  // Membership in metadata.players is the source of truth for whether we're seated or just watching — re-derive it on
  // every update so a spectator who clicks "Join this game" picks up their new seat as soon as this push arrives.
  const isSpectator = !(session.me && metadata.players[session.me.id]);
  session.game.isSpectator = isSpectator;
  applySpectatorUi(isSpectator);

  if (metadata.status === "InProgress") {
    $("start-game").classList.add("hidden"); // the game is live; Start no longer applies
    $("join-as-player").classList.add("hidden"); // too late to join — the match already started
    $("post-game-bar").classList.add("hidden"); // a rematch just began; the next GameStateUpdated takes over
    $("rematch-btn").classList.add("hidden");
    return; // game state pushes take over from here
  }
  if (metadata.status === "Finished") {
    $("join-as-player").classList.add("hidden"); // a finished room's roster is fixed; no new seats to join
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
  $("join-as-player").classList.toggle("hidden", !isSpectator || count >= max);
  if (youHost) {
    const ready = metadata.status === "ReadyToStart";
    $("start-game").classList.remove("hidden");
    $("start-game").disabled = !ready;
    setStatus(ready ? "Opponent joined — press Start." : `You're hosting — waiting for an opponent… (${count}/${max})`);
  } else if (isSpectator) {
    setStatus(`Hosted by ${hostName} — spectating (${count}/${max})`);
  } else {
    $("start-game").classList.add("hidden");
    setStatus(`Hosted by ${hostName} — waiting for them to start… (${count}/${max})`);
  }
}

// A spectator takes a seat in the still-open lobby they're already watching. Reuses the normal join endpoint — a
// spectator is just a subscriber with no seat, so joining is exactly what a fresh joiner does. No resubscribe needed:
// we're already registered for push events, and the resulting LobbyUpdated (fanned out to every subscriber) is what
// flips session.game.isSpectator and refreshes the UI above.
async function joinAsPlayer() {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const res = await api(`/lobby/${game.roomId}/join`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not join the game");
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
  if (game) {
    if (game.isSpectator) {
      // a spectator never took a seat, so /lobby/leave (which forfeits a seated player) doesn't apply — unsubscribe
      // from whichever side (lobby or live game) we're currently watching instead
      const path = game.isLive ? `/${game.gameType}/${game.roomId}/subscribe` : `/lobby/${game.roomId}/subscribe`;
      await api(path, { method: "DELETE" });
    } else {
      await api(`/lobby/${game.roomId}/leave`, { method: "POST", body: {} });
    }
  }
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

// --- Debug trace log ---------------------------------------------------------------------------------------------
// Live view of actor message tracing (see TraceRoutes). A second, independent socket from the main one above — opened
// lazily on first visit to the Debug panel rather than at sign-in, since most sessions never open it. The server
// rejects the upgrade with 503 when tracing is disabled server-side, which surfaces here as a status line rather than
// a console error. Every TraceEvent on this socket is plain (untagged) JSON, unlike the main socket's
// type-discriminated push events, since this socket carries only one event shape.
const TRACE_LOG_CAP = 300; // bounds DOM growth on a long-running session; oldest rows are dropped past this

function showDebug() {
  showPanel("debug");
  if (!session.traceWs) connectTraceWs();
}

function connectTraceWs() {
  const proto = location.protocol === "https:" ? "wss" : "ws";
  const ws = new WebSocket(`${proto}://${location.host}/ws/trace?access_token=${encodeURIComponent(session.token)}`);
  session.traceWs = ws;
  setDebugStatus("Connecting…");
  ws.onopen = () => setDebugStatus("Connected — streaming live trace events.");
  ws.onerror = () => {
    /* the close handler below reports the failure; nothing additional to do here */
  };
  ws.onclose = () => {
    session.traceWs = null;
    setDebugStatus("Disconnected (tracing may be disabled on this server).");
  };
  ws.onmessage = (ev) => {
    let event;
    try {
      event = JSON.parse(ev.data);
    } catch (_) {
      return;
    }
    appendTraceEvent(event);
  };
}

function disconnectTraceWs() {
  if (session.traceWs) {
    try {
      session.traceWs.close();
    } catch (_) {
      /* ignore */
    }
    session.traceWs = null;
  }
}

function setDebugStatus(text) {
  $("debug-status").textContent = text;
}

// Render one TraceEvent as a row, appended at the bottom (the server streams oldest first), capped to
// TRACE_LOG_CAP rows so a chatty server can't grow the table unboundedly.
function appendTraceEvent(event) {
  const body = $("trace-log-body");
  const tr = document.createElement("tr");

  const time = document.createElement("td");
  time.textContent = formatTraceTime(event.timestamp);

  const to = document.createElement("td");
  to.className = "trace-to";
  to.textContent = shortActorPath(event.to);
  to.title = event.to; // full path on hover, since the cell itself is truncated

  const type = document.createElement("td");
  type.className = "trace-type";
  type.textContent = event.messageType;

  tr.append(time, to, type);
  body.appendChild(tr);
  while (body.children.length > TRACE_LOG_CAP) body.removeChild(body.firstChild);

  const wrap = $("trace-log-wrap");
  wrap.scrollTop = wrap.scrollHeight; // keep the latest event in view
}

// Actor paths are full URIs like "pekko://GameManagerSystem/user/lobby-manager"; showing just the "user/..." tail
// keeps the column readable. Falls back to the full string if it doesn't match the expected shape.
function shortActorPath(path) {
  const match = path.match(/\/user\/.*$/);
  return match ? match[0].slice(1) : path;
}

function formatTraceTime(iso) {
  const d = new Date(iso);
  if (isNaN(d.getTime())) return iso;
  return d.toLocaleTimeString([], { hour12: false }) + "." + String(d.getMilliseconds()).padStart(3, "0");
}

// --- Board rendering -------------------------------------------------------------------------------------------------
// Sends a Pig action ("roll" or "hold") as a POST move to the server.
async function submitPigAction(action) {
  const game = session.game;
  if (!game) return;
  setError("game-error", "");
  const res = await api(`/${game.gameType}/${game.roomId}/move`, { method: "POST", body: { action } });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Move rejected");
}

// Renders a Pig game state: a score table, turn info, and Roll / Hold action buttons.
function renderPigBoard(state) {
  const board = $("board");
  board.classList.remove("bs-active");
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

  // Turn info
  const info = document.createElement("p");
  info.className = "pig-turn-info";
  const rollText = state.lastRoll != null ? `Last roll: ${state.lastRoll}` : "No roll yet this turn";
  info.textContent = `Turn score: ${state.turnScore}  |  ${rollText}`;
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

// Renders any game-state view pushed from the server. Battleship states (identified by `state.board1`) are handled
// separately; Pig states (identified by `state.scores` being an object) use their own renderer; all other games use
// the shared grid renderer below.
function renderBoard(state) {
  // a board push means we're now (or still) watching a live game, however we got here
  if (session.game) session.game.isLive = true;
  $("start-game").classList.add("hidden");
  $("post-game-bar").classList.add("hidden");
  $("rematch-btn").classList.add("hidden");
  setError("game-error", "");
  $("board").classList.remove("ld-active"); // cleared here so switching away from Liar's Dice restores the board grid
  $("board").classList.remove("holdem-active"); // likewise for the free-sized Texas Hold 'Em table

  if (state.board1 !== undefined) {
    renderBattleshipBoard(state);
    return;
  }

  if (state.scores !== undefined && !Array.isArray(state.scores)) {
    renderPigBoard(state);
    return;
  }

  if (state.guessesRemaining !== undefined) {
    renderMastermindBoard(state);
    return;
  }

  if (state.diceCounts !== undefined) {
    renderLiarsDiceBoard(state);
    return;
  }

  if (state.seats !== undefined) {
    renderTexasHoldEmBoard(state);
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

  renderColumnControls(columnMode ? cols : 0, rows, over || spectating);

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

// Renders the dual-board Battleship view. The viewer's own board reveals ships; the opponent's board (and both boards
// for a spectator) shows only fired cells, hiding ship positions until hit.
function renderBattleshipBoard(state) {
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

// Mastermind: the codemaker sets a hidden 4-peg code, then the codebreaker guesses it, receiving black/white peg
// feedback each turn. `state.secret` is only populated when the server chooses to reveal it — to the codemaker, or to
// everyone once the game ends — so the codebreaker never sees the answer early.
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

function renderMastermindBoard(state) {
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

// Liar's Dice: each player hides five dice; on your turn you either raise the bid (a quantity + face over all dice on
// the table, with 1s wild) or call "Liar". `state.dice` is only your own hand — other seats show just a count — until a
// challenge reveals every die in `state.lastReveal`, the one public moment.
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

// --- Texas Hold 'Em --------------------------------------------------------------------------------------------------
// The community board, a seat table (stacks, bets, folded/all-in), the viewer's own hole cards, the last hand's
// showdown reveal, and betting controls. The server validates every bet/raise amount and rejects an illegal one.
const HOLDEM_SUITS = { S: "♠", H: "♥", C: "♣", D: "♦" };

// The Hold 'Em pre-action ("advance action") the viewer has armed while waiting for the turn to reach them, or null.
// It is a client-only convenience — nothing is sent until it is the viewer's turn, at which point the armed choice is
// auto-submitted as a normal in-turn move. Shape: { kind, street, button, currentBet, toCall } where the last four are
// the betting context captured when it was armed, used to invalidate a choice the action has since outrun.
let holdemPreAction = null;

function renderTexasHoldEmBoard(state) {
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

  // The community cards (revealed cards face-up, the rest face-down) and the pot.
  const cards = document.createElement("div");
  cards.className = "holdem-board";
  for (let i = 0; i < 5; i++) cards.appendChild(makeHoldemCard(i < state.board.length ? state.board[i] : null));
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

  // The most recent hand's showdown reveal — the one moment hole cards come up.
  if (state.handResult) board.appendChild(renderHoldemResult(state.handResult));

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
function renderHoldemResult(result) {
  const box = document.createElement("div");
  box.className = "holdem-reveal";
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

function renderLiarsDiceBoard(state) {
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
  for (const id of ["login", "lobby", "game", "debug"]) $(id).classList.toggle("hidden", id !== name);
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
$("debug-nav").addEventListener("click", showDebug);
$("debug-back-to-lobby").addEventListener("click", enterLobby);

for (const button of document.querySelectorAll("[data-gametype]")) {
  button.addEventListener("click", () => createGame(button.dataset.gametype));
}

$("refresh-lobbies").addEventListener("click", () => {
  refreshMySessions();
  refreshLobbies();
});
$("lobby-filter").addEventListener("change", refreshLobbies);
$("start-game").addEventListener("click", startGame);
$("join-as-player").addEventListener("click", joinAsPlayer);
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
