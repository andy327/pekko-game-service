// The authentication seam. login/register/playAsGuest are the only places that know how a token is obtained; each hands
// off to completeSignIn for everything downstream (persist, load profile, open the socket, route). Adding another auth
// method touches only these. Also owns session restore on reload and logout teardown.

import { $, session } from "./state.js";
import { api } from "./api.js";
import { connectWs } from "./socket.js";
import { disconnectTraceWs } from "./debug.js";
import { showPanel } from "./view.js";
import { enterLobby } from "./lobby.js";
import { consumePendingJoinRoomId, joinByRoomId } from "./deeplinks.js";

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
export async function login(email, password) {
  const res = await api("/auth/token", { method: "POST", body: { email, password }, auth: false });
  if (!res.ok) throw new Error(res.data?.error || "Invalid email or password");
  await completeSignIn(res.data.token);
}

// Create a new account and sign in.
export async function register(username, email, password) {
  const res = await api("/auth/register", { method: "POST", body: { username, email, password }, auth: false });
  if (!res.ok) throw new Error(res.data?.error || `Registration failed (${res.status})`);
  await completeSignIn(res.data.token);
}

// Guest play: register a throwaway account behind the scenes so the user only types a display name.
export async function playAsGuest(name) {
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
export async function restoreSession() {
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
  const pending = consumePendingJoinRoomId();
  if (pending) joinByRoomId(pending);
  else enterLobby();
}

// Clear the session (token, profile, socket) and return to the login screen.
export function logout() {
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
