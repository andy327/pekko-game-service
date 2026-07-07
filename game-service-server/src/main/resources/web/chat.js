// In-room chat. Outbound messages are ClientMessage.ChatSend frames on the main WebSocket (socket.js); inbound
// ChatMessage pushes arrive on that same socket and are rendered here.
//
// Chat rides the same one-per-player WebSocket. Outbound messages are ClientMessage.ChatSend frames; the server fans
// the resulting ChatMessage back to every subscriber (including the sender), so — like the board — we render purely
// from received events rather than echoing locally.

import { $, session } from "./state.js";
import { api } from "./api.js";

export function appendChat(m) {
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
export async function loadChatHistory(roomId, gameType) {
  const res = await api(`/${gameType}/${roomId}/chat`);
  if (res.ok && res.data && Array.isArray(res.data.messages)) res.data.messages.forEach(appendChat);
}

// Send a chat frame over the WebSocket. Requires an open socket and an active game/lobby context.
export function sendChat(text) {
  if (!session.ws || session.ws.readyState !== WebSocket.OPEN || !session.game) return;
  session.ws.send(JSON.stringify({ type: "ChatSend", roomId: session.game.roomId, text }));
}
