// The lobby and game-room lifecycle: creating and joining games, listing open lobbies and the player's own sessions,
// spectating, entering/resuming/leaving a room, and reacting to LobbyUpdated / GameEnded pushes. This is the hub that
// ties the lobby screen and the game screen together; the actual board drawing lives in board.js and games/*.js.

import { $, session, GAMES } from "./state.js";
import { setStatus, setError, flashError, showPanel } from "./view.js";
import { api } from "./api.js";
import { loadChatHistory } from "./chat.js";
import { resetHoldemPreAction } from "./games/holdem.js";
import { resetCopyLinkButton } from "./deeplinks.js";

// Bot seats hold ids minted from a reserved namespace (see BotId on the server), so every bot id carries this prefix
// and the client can spot one without the server tagging each player. Deliberately keyed on the id rather than the
// display name: a guest is free to call themselves "Bot 1", but they cannot mint an id in this namespace.
const BOT_ID_PREFIX = "b07b07b0-7b07-b07b-";
const isBotId = (id) => id.startsWith(BOT_ID_PREFIX);

export async function createGame(gameType) {
  setError("lobby-error", "");
  // Optional host-chosen lobby name; the server trims it and treats a blank value as unnamed.
  const name = $("lobby-name").value.trim();
  const query = name ? `?name=${encodeURIComponent(name)}` : "";
  const res = await api(`/lobby/create/${gameType}${query}`, { method: "POST", body: {} });
  if (!res.ok) return flashError("lobby-error", res.data?.error || res.data || "Could not create game");
  $("lobby-name").value = "";
  await enterGame({ roomId: res.data.roomId, gameType, isHost: true });
}

export async function joinGame(roomId, gameType) {
  setError("lobby-error", "");
  const res = await api(`/lobby/${roomId}/join`, { method: "POST", body: {} });
  if (!res.ok) return flashError("lobby-error", res.data?.error || res.data || "Could not join game");
  await enterGame({ roomId, gameType, isHost: false });
}

export async function refreshLobbies() {
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
    const named = lobby.name ? `${lobby.name} — ` : ""; // host-chosen label leads the line when present
    const meta = document.createElement("span");
    meta.className = "meta";
    // textContent (not innerHTML) renders the player-supplied name as literal text, so it can't inject markup.
    meta.textContent = phase
      ? `${named}${GAMES[type].label} — hosted by ${hostName} — ${phase}${watching}`
      : `${named}${GAMES[type].label} — hosted by ${hostName} — ${count}/${max} players${watching}`;

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
  resetHoldemPreAction(); // drop any armed Hold 'Em pre-action from a previous game
  $("game-title").textContent = GAMES[gameType].label;
  $("board").innerHTML = "";
  $("column-controls").innerHTML = "";
  setError("game-error", "");
  $("start-game").classList.add("hidden");
  $("start-game").disabled = true;
  $("add-bot").classList.add("hidden"); // shown only for the host of a not-yet-full pre-game lobby (onLobbyUpdated)
  $("lobby-roster").classList.add("hidden"); // populated from the first LobbyUpdated push
  $("lobby-roster").innerHTML = "";
  $("post-game-bar").classList.add("hidden");
  $("rematch-btn").classList.add("hidden");
  resetCopyLinkButton();
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
export function enterLobby() {
  showPanel("lobby");
  refreshMySessions();
  refreshLobbies();
}

// Load the player's current participation (joined lobbies + in-progress games) from live actor state and render
// Return entries. Best-effort: the open-lobby list remains the primary view if this fails.
export async function refreshMySessions() {
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
    const named = l.name ? `${l.name} — ` : "";
    const label = `${named}${GAMES[type].label} — ${phase}${youHost ? " · you host" : ""}`;
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
export function onLobbyUpdated(metadata) {
  if (!session.game || metadata.roomId !== session.game.roomId) return;

  // Membership in metadata.players is the source of truth for whether we're seated or just watching — re-derive it on
  // every update so a spectator who clicks "Join this game" picks up their new seat as soon as this push arrives.
  const isSpectator = !(session.me && metadata.players[session.me.id]);
  session.game.isSpectator = isSpectator;
  applySpectatorUi(isSpectator);

  if (metadata.status === "InProgress") {
    $("start-game").classList.add("hidden"); // the game is live; Start no longer applies
    $("add-bot").classList.add("hidden"); // seats are locked once the match starts
    $("lobby-roster").classList.add("hidden"); // the board takes over; the roster is a pre-game view
    $("join-as-player").classList.add("hidden"); // too late to join — the match already started
    $("post-game-bar").classList.add("hidden"); // a rematch just began; the next GameStateUpdated takes over
    $("rematch-btn").classList.add("hidden");
    return; // game state pushes take over from here
  }
  if (metadata.status === "Finished") {
    $("add-bot").classList.add("hidden"); // a finished room's roster is fixed
    $("lobby-roster").classList.add("hidden"); // the final board stays in view instead
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
  // only the host manages bot seats, and only while a seat is still open
  $("add-bot").classList.toggle("hidden", !youHost || count >= max);
  renderRoster(metadata, youHost);
  if (youHost) {
    const ready = metadata.status === "ReadyToStart";
    $("start-game").classList.remove("hidden");
    $("start-game").disabled = !ready;
    setStatus(
      ready
        ? "Opponent seated — press Start, or add another."
        : `You're hosting — add a bot or wait for a player… (${count}/${max})`
    );
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
export async function joinAsPlayer() {
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

export async function rematch() {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.roomId}/start`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not start the rematch");
  // On success a fresh GameStateUpdated arrives over the WebSocket and clears the post-game bar.
}

export async function startGame() {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.roomId}/start`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not start the game");
  // On success the game-state push arrives over the WebSocket and renders the board.
}

// Host-only: seat an AI player. The resulting LobbyUpdated (fanned to every subscriber) refreshes the roster and
// flips the room to ReadyToStart, which enables Start — so this only needs to fire the request.
export async function addBot() {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.roomId}/bots`, { method: "POST", body: {} });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not add a bot");
}

// Host-only: free a bot's seat, so the host can drop a surplus bot or make room for a human. Like addBot, the
// resulting LobbyUpdated re-renders the roster and recomputes whether Start is still available.
async function removeBot(botId) {
  setError("game-error", "");
  const res = await api(`/lobby/${session.game.roomId}/bots/${botId}`, { method: "DELETE" });
  if (!res.ok) flashError("game-error", res.data?.error || res.data || "Could not remove the bot");
}

// Draw the seated players for a pre-game room: who's here, which one is you, who hosts, and — for the host — a
// Remove button on each bot. Rendered from metadata.players, the same source the (n/max) count comes from, so the
// list and the count can never disagree.
function renderRoster(metadata, youHost) {
  const ul = $("lobby-roster");
  ul.innerHTML = "";
  // metadata.players is a map, so its order is arbitrary and can shuffle between pushes; sort for a stable list —
  // the host leads, then everyone else by name (which orders bots by their ordinal)
  const players = Object.values(metadata.players).sort((a, b) => {
    if (a.id === metadata.hostId) return -1;
    if (b.id === metadata.hostId) return 1;
    return a.name.localeCompare(b.name);
  });

  for (const player of players) {
    const li = document.createElement("li");
    const isYou = Boolean(session.me) && player.id === session.me.id;
    if (isYou) li.classList.add("you");

    const tags = [];
    if (player.id === metadata.hostId) tags.push("host");
    if (isYou) tags.push("you");

    const meta = document.createElement("span");
    meta.className = "meta";
    // textContent, not innerHTML: a player-chosen display name renders as literal text and can't inject markup
    meta.textContent = tags.length > 0 ? `${player.name} (${tags.join(", ")})` : player.name;
    li.appendChild(meta);

    // only the host manages bot seats, and only before the match starts
    if (youHost && isBotId(player.id)) {
      const remove = document.createElement("button");
      remove.textContent = "Remove";
      remove.onclick = () => removeBot(player.id);
      li.appendChild(remove);
    }

    ul.appendChild(li);
  }

  ul.classList.remove("hidden");
}

export function onGameEnded(result) {
  if (result === "Cancelled") setStatus("Game cancelled.");
  // A "Completed" end is already reflected by the final GameStateUpdated (winner/draw), so leave that status in place.
}

export async function leaveGame() {
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
