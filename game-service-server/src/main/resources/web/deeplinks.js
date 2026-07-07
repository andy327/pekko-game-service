// Shareable invite links, both ends of the scheme. A `#join/<roomId>` URL hash drops a visitor straight into a specific
// lobby; `copyInviteLink` produces such a URL for the current room. The hash keeps the request on the static-served `/`
// route (a path like /lobby/<id> is a JSON API route). It is captured once at load and consumed after the visitor signs
// in, since joining needs a token and an open WebSocket.
//
// Note: this module and lobby.js reference each other (deeplinks joins via lobby; lobby resets the copy button here).
// That import cycle is safe because every cross-reference happens inside a function called at runtime, never during
// module evaluation.

import { $, session, GAMES } from "./state.js";
import { api } from "./api.js";
import { flashError } from "./view.js";
import { enterLobby, joinGame } from "./lobby.js";

// Parse a #join/<roomId> invite hash, or null. Captured at load and also watched live via hashchange below.
function parseJoinHash() {
  return (location.hash.match(/^#join\/([0-9a-fA-F-]+)$/) || [])[1] || null;
}

// A pending invite to consume once the visitor is signed in. Set from the initial hash and from later hash changes.
let pendingJoinRoomId = parseJoinHash();
if (pendingJoinRoomId) history.replaceState(null, "", location.pathname + location.search);

// Hand the pending invite (if any) to the caller and clear it, so it is followed exactly once after sign-in.
export function consumePendingJoinRoomId() {
  const id = pendingJoinRoomId;
  pendingJoinRoomId = null;
  return id;
}

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
export async function joinByRoomId(roomId) {
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
export async function copyInviteLink() {
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

// Restore the copy-link button to its resting label and cancel any pending flip — called when a fresh game view is
// prepared (lobby.js) so switching rooms can't leave it stuck on "Link copied!".
export function resetCopyLinkButton() {
  clearTimeout(copyLinkTimer);
  $("copy-link").textContent = COPY_LINK_LABEL;
}
