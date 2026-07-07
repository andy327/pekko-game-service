// Small view helpers shared across screens: switching the visible panel, writing the game status line, and setting or
// flashing an error message. Kept dependency-light (only the `$` lookup) so any module can pull them in.

import { $ } from "./state.js";

// Show one of the top-level panels (login / lobby / game / debug) and hide the rest.
export function showPanel(name) {
  for (const id of ["login", "lobby", "game", "debug"]) $(id).classList.toggle("hidden", id !== name);
}

export function setStatus(text) {
  $("game-status").textContent = text;
}

export function setError(id, text) {
  $(id).textContent = text;
  clearTimeout(errorTimers[id]); // cancel any pending auto-dismiss; an explicit set/clear wins
}

// Show a transient error that auto-dismisses after a few seconds, so a rejected action (e.g. an illegal move that
// triggers no state update to clear it) doesn't leave a message stranded on screen.
const errorTimers = {};
export function flashError(id, text) {
  setError(id, text);
  if (text) errorTimers[id] = setTimeout(() => setError(id, ""), 5000);
}
