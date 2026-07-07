// The REST helper every module uses to talk to the JSON API. Attaches the bearer token from `session` and normalises
// the response into a single { ok, status, data } shape.

import { session } from "./state.js";

// Returns { ok, status, data } where data is parsed JSON when the response is JSON, otherwise the raw text (the API
// returns plain-text bodies for move rejections and other errors).
export async function api(path, opts = {}) {
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
