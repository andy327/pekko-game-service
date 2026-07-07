// The main per-player WebSocket: opening it and dispatching the server's tagged push events to the right handler. Chat
// rides this same socket (see chat.js); the debug trace stream is a separate socket (see debug.js).

import { session } from "./state.js";
import { onLobbyUpdated, onGameEnded } from "./lobby.js";
import { renderBoard } from "./board.js";
import { appendChat } from "./chat.js";

// One socket per player. The browser WebSocket API cannot set headers, so the JWT travels as the access_token query
// parameter (the server accepts it there for /ws only). Resolves once the socket is open so subscribe calls — which
// require a registered PlayerActor — never race ahead of the connection.
export function connectWs() {
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
