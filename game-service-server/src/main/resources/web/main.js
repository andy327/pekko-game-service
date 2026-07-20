// Entry point for the Pekko game service web client. Plain ES modules, no build step, served same-origin with the API.
//
// Flow: sign in -> open one WebSocket -> create or join a lobby and subscribe to it -> the host starts the game.
// Lobby subscribers are carried over to the game actor on start, so each player begins receiving GameStateUpdated
// pushes automatically; this client never re-subscribes once a game begins. Moves are fired optimistically over REST
// and the board is redrawn purely from the WebSocket pushes, with the server as the sole authority on legality.
//
// This module owns only the DOM wiring and startup; the behaviour lives in the imported modules:
//   state      - shared session state and the game registry
//   api        - the REST helper
//   view       - panel switching, status and error helpers
//   socket     - the main WebSocket and its event dispatch
//   auth       - sign-in / register / guest / restore / logout
//   lobby      - lobby and game-room lifecycle
//   chat       - in-room chat
//   debug      - the trace-log panel
//   board      - board dispatch + the shared grid renderer
//   deeplinks  - shareable #join/<roomId> invite links
//   games/*    - one module per game with a bespoke board view; each self-registers its renderer on its GAMES entry

import { $ } from "./state.js";
import { setError, showPanel } from "./view.js";
import { login, register, playAsGuest, logout, restoreSession } from "./auth.js";
import {
  createGame,
  enterLobby,
  refreshMySessions,
  refreshLobbies,
  startGame,
  joinAsPlayer,
  rematch,
  leaveGame
} from "./lobby.js";
import { showDebug } from "./debug.js";
import { copyInviteLink } from "./deeplinks.js";
import { sendChat } from "./chat.js";

// Per-game board renderers self-register on the GAMES registry (state.js) when their module is imported; pulling them
// in here wires them up. Adding a new game with a bespoke board means adding its module to this list.
import "./games/pig.js";
import "./games/battleship.js";
import "./games/mastermind.js";
import "./games/liarsdice.js";
import "./games/holdem.js";
import "./games/checkers.js";

// --- Auth screen ---------------------------------------------------------------------------------------------------

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

// --- Lobby & game controls -----------------------------------------------------------------------------------------

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
