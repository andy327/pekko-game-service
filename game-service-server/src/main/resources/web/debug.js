// The Debug panel's live actor-message trace log. A second, independent WebSocket from the main one (socket.js), opened
// lazily on first visit since most sessions never open it.
//
// The server rejects the upgrade with 503 when tracing is disabled server-side, which surfaces here as a status line
// rather than a console error. Every TraceEvent on this socket is plain (untagged) JSON, unlike the main socket's
// type-discriminated push events, since this socket carries only one event shape.

import { $, session } from "./state.js";
import { showPanel } from "./view.js";

const TRACE_LOG_CAP = 300; // bounds DOM growth on a long-running session; oldest rows are dropped past this

export function showDebug() {
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

export function disconnectTraceWs() {
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
