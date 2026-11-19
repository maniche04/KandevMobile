#!/usr/bin/env node
// forge-relay: forwards encrypted Kandev agent alerts through direct FCM or legacy Web Push
// (RFC 8291 aes128gcm + RFC 8292 VAPID), so alerts reach the phone even when
// Kandev itself is unreachable (Tailscale off, away from home). Zero dependencies; Node 22+.
//
// Environment:
//   KANDEV_URL     Kandev origin (default http://127.0.0.1:38429)
//   KANDEV_TOKEN   Kandev API token, if authentication is enabled
//   RELAY_HOST     listen address (default 0.0.0.0)   RELAY_PORT  (default 38430)
//   RELAY_STATE    state directory (default ~/.forge-relay)
//   GOOGLE_APPLICATION_CREDENTIALS  service-account JSON path for direct FCM
//
// HTTP API (all JSON):
//   GET    /v1/health                     liveness and Kandev link state
//   GET    /v1/info                       { vapid_public_key, version, fcm }
//   POST   /v1/devices          (pairing) { id, transport:"fcm", token, encryption_key, name }
//                                       or legacy { id, endpoint, p256dh, auth, name }
//   DELETE /v1/devices/:id      (pairing)
//   GET    /v1/devices/:id      (pairing) { ok, transport, name, added, last } or 404 when not registered
//   POST   /v1/devices/:id/presence (pairing) { task_id|null, ttl } the task on screen; its alerts are skipped
//   POST   /v1/devices/:id/test (pairing) sends a test alert
// "(pairing)" routes need `Authorization: Bearer <pairing key>`; the key is in state.json.

import { createServer } from "node:http";
import crypto from "node:crypto";
import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import { encrypt } from "./webpush.mjs";
import { createFcm } from "./fcm.mjs";
import { notificationBody, notificationIdentity } from "./notification-content.mjs";

const KANDEV_URL = (process.env.KANDEV_URL || "http://127.0.0.1:38429").replace(/\/$/, "");
const KANDEV_TOKEN = process.env.KANDEV_TOKEN || "";
const HOST = process.env.RELAY_HOST || "0.0.0.0";
const PORT = Number(process.env.RELAY_PORT || 38430);
const STATE_DIR = process.env.RELAY_STATE || path.join(os.homedir(), ".forge-relay");
const STATE_FILE = path.join(STATE_DIR, "state.json");

const fcm = process.env.GOOGLE_APPLICATION_CREDENTIALS
  ? createFcm(JSON.parse(fs.readFileSync(process.env.GOOGLE_APPLICATION_CREDENTIALS, "utf8"))) : null;

const log = (...args) => console.log(new Date().toISOString(), ...args);
const b64u = (buf) => Buffer.from(buf).toString("base64url");
const unb64u = (text) => Buffer.from(text, "base64url");

// ---------------------------------------------------------------------------------------------
// State: VAPID key pair, pairing key and registered devices. Written atomically, mode 0600.

function loadState() {
  fs.mkdirSync(STATE_DIR, { recursive: true, mode: 0o700 });
  let state = {};
  try { state = JSON.parse(fs.readFileSync(STATE_FILE, "utf8")); } catch {}
  if (!state.vapid) {
    const { privateKey, publicKey } = crypto.generateKeyPairSync("ec", { namedCurve: "P-256" });
    const jwk = publicKey.export({ format: "jwk" });
    state.vapid = {
      publicKey: b64u(Buffer.concat([Buffer.from([4]), unb64u(jwk.x), unb64u(jwk.y)])),
      privateKey: privateKey.export({ format: "pem", type: "pkcs8" }),
    };
  }
  if (!state.pairingKey) state.pairingKey = crypto.randomBytes(9).toString("base64url");
  state.devices ||= {};
  saveState(state);
  return state;
}

function saveState(value) {
  const temp = `${STATE_FILE}.tmp`;
  fs.writeFileSync(temp, JSON.stringify(value, null, 2), { mode: 0o600 });
  fs.renameSync(temp, STATE_FILE);
}

const state = loadState();
const vapidKey = crypto.createPrivateKey(state.vapid.privateKey);

// ---------------------------------------------------------------------------------------------
// Web Push encryption (RFC 8291) and VAPID (RFC 8292).

function vapidHeader(endpoint) {
  const audience = new URL(endpoint).origin;
  const claims = { aud: audience, exp: Math.floor(Date.now() / 1000) + 12 * 3600, sub: "https://github.com/kdlbs/kandev" };
  const unsigned = `${b64u(JSON.stringify({ typ: "JWT", alg: "ES256" }))}.${b64u(JSON.stringify(claims))}`;
  const signature = crypto.sign("sha256", Buffer.from(unsigned), { key: vapidKey, dsaEncoding: "ieee-p1363" });
  return `vapid t=${unsigned}.${b64u(signature)}, k=${state.vapid.publicKey}`;
}

/** Last delivery result per device, for GET /v1/devices/:id. In memory only. */
const lastPush = new Map();

function removeDevice(id, reason) {
  if (!state.devices[id]) return;
  log("removing device", id, state.devices[id].name, `(${reason})`);
  delete state.devices[id];
  lastPush.delete(id); presence.delete(id);
  saveState(state);
}

async function push(device, alert, urgency = "normal") {
  const started = Date.now();
  const status = await deliver(device, alert, urgency).catch((error) => {
    lastPush.set(device.id, { at: new Date().toISOString(), kind: alert.kind, error: error.message });
    throw error;
  });
  lastPush.set(device.id, { at: new Date().toISOString(), kind: alert.kind, status });
  log("push", alert.kind, device.id, status, `${Date.now() - started}ms`);
  return status;
}

async function deliver(device, alert, urgency) {
  if (device.transport === "fcm") {
    if (!fcm) throw new Error("FCM is not configured on the relay");
    const result = await fcm.send(device, alert);
    if (result.unregistered) removeDevice(device.id, "FCM token unregistered");
    else if (result.status >= 300) log("FCM push failed", device.id, result.status, result.error);
    return result.status;
  }
  const plaintext = Buffer.from(JSON.stringify({ v: 1, ...alert }));
  const response = await fetch(device.endpoint, {
    method: "POST",
    headers: {
      "Content-Encoding": "aes128gcm", "Content-Type": "application/octet-stream",
      TTL: "86400", Urgency: urgency, Authorization: vapidHeader(device.endpoint),
    },
    body: encrypt(device, plaintext),
    signal: AbortSignal.timeout(15_000),
  });
  if (response.status === 404 || response.status === 410) removeDevice(device.id, "endpoint gone");
  else if (!response.ok) {
    log("push failed", device.id, response.status, (await response.text()).slice(0, 200));
  }
  return response.status;
}

/** The task each phone has on screen, reported by the app; alerts for it would show nothing. */
const presence = new Map();

function onScreen(deviceId, taskId) {
  const seen = presence.get(deviceId);
  if (!seen || seen.until < Date.now()) { presence.delete(deviceId); return false; }
  return seen.task === taskId;
}

async function broadcast(alert) {
  const urgency = alert.kind === "needs_you" ? "high" : "normal";
  // Every HIGH push must end in a notification, or FCM lowers this app's priority: skip the task on screen.
  const targets = Object.values(state.devices).filter((device) => {
    if (!onScreen(device.id, alert.task_id)) return true;
    log("skip", alert.kind, device.id, "(on screen)");
    return false;
  });
  const results = await Promise.allSettled(targets.map((device) => push(device, alert, urgency)));
  for (const result of results) if (result.status === "rejected") log("push error", result.reason.message);
}

// ---------------------------------------------------------------------------------------------
// Kandev watcher: derives alerts from the workspace feed (user.subscribe).

const authHeaders = KANDEV_TOKEN ? { Authorization: `Bearer ${KANDEV_TOKEN}` } : {};
const kandev = async (pathname) => {
  const response = await fetch(`${KANDEV_URL}${pathname}`, { headers: { Accept: "application/json", ...authHeaders }, signal: AbortSignal.timeout(15_000) });
  if (!response.ok) throw new Error(`Kandev ${pathname} -> ${response.status}`);
  return response.json();
};

/** Last known per-task signals; alerts fire on transitions only. */
const known = new Map();
const pendingTimers = new Map();
let linkState = "connecting";

function signals(task) {
  const summary = task.status_summary || {};
  const sessionState = summary.primary_session?.state || task.primary_session_state || "";
  return {
    running: Boolean(summary.has_running_session) || ["RUNNING", "STARTING"].includes(sessionState),
    pending: summary.pending_action || task.primary_session_pending_action || task.task_pending_action || null,
    sessionState,
    state: task.state,
  };
}

async function seed() {
  const { workspaces = [] } = await kandev("/api/v1/workspaces");
  for (const workspace of workspaces) {
    for (let page = 1; page < 50; page++) {
      const { tasks = [], total = 0 } = await kandev(`/api/v1/workspaces/${workspace.id}/tasks?page=${page}&page_size=100`);
      for (const task of tasks) {
        const now = signals(task), before = known.get(task.id);
        // After an outage, keep the old state for changed tasks so evaluate() still alerts on what was missed.
        if (before && (before.running !== now.running || Boolean(before.pending) !== Boolean(now.pending))) evaluate(task.id);
        else known.set(task.id, now);
      }
      if (tasks.length === 0 || page * 100 >= total) break;
    }
  }
  log(`watching ${known.size} tasks across ${workspaces.length} workspaces`);
}

let agentCatalog = [], agentCatalogAt = 0;
async function agents() {
  if (Date.now() - agentCatalogAt < 60_000) return agentCatalog;
  try {
    agentCatalog = (await kandev("/api/v1/agents")).agents || [];
    agentCatalogAt = Date.now();
  } catch { /* Keep the last catalog if Kandev is temporarily unavailable. */ }
  return agentCatalog;
}

async function recentMessages(sessionId) {
  if (!sessionId) return [];
  try {
    return (await kandev(`/api/v1/task-sessions/${sessionId}/messages?limit=40&sort=desc`)).messages || [];
  } catch { return []; }
}

/** Re-reads one task (coalescing bursts) and alerts on meaningful transitions. */
function evaluate(taskId) {
  clearTimeout(pendingTimers.get(taskId));
  pendingTimers.set(taskId, setTimeout(async () => {
    pendingTimers.delete(taskId);
    let task;
    try { task = await kandev(`/api/v1/tasks/${taskId}`); } catch { return; }
    const now = signals(task);
    const before = known.get(taskId);
    known.set(taskId, now);
    if (!before || Object.keys(state.devices).length === 0) return;
    let kind = null;
    if (now.pending && !before.pending) {
      // Auto-approved permissions resolve within a second; alert only if the request is still open.
      await new Promise((resolve) => setTimeout(resolve, 3000));
      try {
        const confirmed = signals(await kandev(`/api/v1/tasks/${taskId}`));
        known.set(taskId, confirmed);
        if (!confirmed.pending) return;
      } catch { return; }
      kind = "needs_you";
    } else if (before.running && !now.running) {
      const failed = now.sessionState === "FAILED" || now.state === "FAILED";
      kind = failed ? "failed" : "turn_finished";
    }
    if (!kind) return;
    const [messages, catalog] = await Promise.all([recentMessages(task.primary_session_id), agents()]);
    const body = notificationBody(kind, now.pending, messages, task.status_summary?.active_error?.preview);
    // Web Push payloads are limited to ~4 KB: keep the alert compact.
    const alert = {
      kind, task_id: task.id, session_id: task.primary_session_id, workspace_id: task.workspace_id,
      title: (task.title || "Agent update").slice(0, 200), body: body.slice(0, 1500), ...notificationIdentity(task, catalog),
      state: task.state, session_state: now.sessionState, pending_action: now.pending, at: new Date().toISOString(),
    };
    log("alert", kind, task.id, alert.title);
    await broadcast(alert);
  }, 1500));
}

function watch() {
  const url = KANDEV_URL.replace(/^http/, "ws") + "/ws";
  let backoff = 1000;
  let socket = null;
  let retryTimer = null;
  // Every failure path funnels here, so the watcher can never stall after a Kandev restart.
  const retry = (reason) => {
    linkState = "offline";
    if (retryTimer) return;
    log(`Kandev link down (${reason}); retrying in ${backoff / 1000}s`);
    retryTimer = setTimeout(() => { retryTimer = null; connect(); }, backoff);
    backoff = Math.min(backoff * 2, 60_000);
  };
  const connect = async () => {
    linkState = "connecting";
    try { await seed(); } catch (error) { return retry(`seed failed: ${error.message}`); }
    let current;
    try {
      current = new WebSocket(url, KANDEV_TOKEN ? { headers: authHeaders } : undefined);
    } catch (error) { return retry(`socket failed: ${error.message}`); }
    socket = current;
    const openTimeout = setTimeout(() => { if (linkState !== "live") current.close(); }, 15_000);
    current.onopen = () => {
      clearTimeout(openTimeout);
      linkState = "live"; backoff = 1000;
      current.send(JSON.stringify({ id: crypto.randomUUID(), type: "request", action: "user.subscribe", payload: {} }));
      log("connected to Kandev");
    };
    current.onmessage = (event) => {
      let message;
      try { message = JSON.parse(event.data); } catch { return; }
      if (message.type !== "notification") return;
      const taskId = message.payload?.task_id;
      if (!taskId) return;
      if (["task.status_summary.updated", "session.state_changed", "session.pending_action_changed",
        "task.state_changed", "task.updated", "task.created"].includes(message.action)) evaluate(taskId);
    };
    current.onerror = () => {};
    current.onclose = () => {
      clearTimeout(openTimeout);
      if (socket === current) socket = null;
      retry("closed");
    };
  };
  // Watchdog: a half-open socket or a restarted Kandev must not leave alerts silently dead.
  setInterval(async () => {
    if (linkState !== "live") return;
    try { await kandev("/api/v1/system/info"); }
    catch { socket?.close(); retry("health check failed"); }
  }, 60_000);
  connect();
}

// ---------------------------------------------------------------------------------------------
// HTTP API

function authorized(request) {
  const given = Buffer.from((request.headers.authorization || "").replace(/^Bearer\s+/i, ""));
  const expected = Buffer.from(state.pairingKey);
  return given.length === expected.length && crypto.timingSafeEqual(given, expected);
}

async function readJson(request) {
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 16_384) throw new Error("too large");
    chunks.push(chunk);
  }
  return JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}");
}

const send = (response, status, body) => {
  response.writeHead(status, { "Content-Type": "application/json", "Cache-Control": "no-store" });
  response.end(JSON.stringify(body));
};

createServer(async (request, response) => {
  try {
    const { pathname } = new URL(request.url, "http://relay");
    if (request.method === "GET" && pathname === "/v1/health") {
      return send(response, 200, { ok: true, kandev: linkState, devices: Object.keys(state.devices).length, tasks: known.size });
    }
    if (request.method === "GET" && pathname === "/v1/info") {
      return send(response, 200, { vapid_public_key: state.vapid.publicKey, version: 1, fcm: Boolean(fcm) });
    }
    if (!authorized(request)) return send(response, 401, { error: "pairing key required" });
    if (request.method === "POST" && pathname === "/v1/devices") {
      const body = await readJson(request);
      const validId = typeof body.id === "string" && /^[\w-]{8,64}$/.test(body.id);
      const validFcm = body.transport === "fcm" && typeof body.token === "string" &&
        body.token.length >= 20 && body.token.length <= 4096 &&
        typeof body.encryption_key === "string" && Buffer.from(body.encryption_key, "base64").length === 32;
      const validWebPush = !body.transport && typeof body.endpoint === "string" && /^https:\/\//.test(body.endpoint) &&
        typeof body.p256dh === "string" && unb64u(body.p256dh).length === 65 &&
        typeof body.auth === "string" && unb64u(body.auth).length === 16;
      if (!validId || !(validFcm || validWebPush)) return send(response, 400, { error: "invalid device" });
      if (validFcm && !fcm) return send(response, 503, { error: "FCM is not configured on the relay" });
      state.devices[body.id] = { id: body.id,
        ...(validFcm ? { transport: "fcm", token: body.token, encryption_key: body.encryption_key }
          : { endpoint: body.endpoint, p256dh: body.p256dh, auth: body.auth }),
        name: String(body.name || "Android").slice(0, 80), added: new Date().toISOString() };
      saveState(state);
      log("device registered", body.id, state.devices[body.id].name);
      return send(response, 200, { ok: true });
    }
    const match = pathname.match(/^\/v1\/devices\/([\w-]{8,64})(\/test)?$/);
    if (match && request.method === "GET" && !match[2]) {
      const device = state.devices[match[1]];
      if (!device) return send(response, 404, { error: "unknown device" });
      return send(response, 200, { ok: true, transport: device.transport || "webpush", name: device.name,
        added: device.added, last: lastPush.get(device.id) || null });
    }
    if (match && request.method === "DELETE" && !match[2]) {
      removeDevice(match[1], "DELETE request");
      return send(response, 200, { ok: true });
    }
    const presenceMatch = pathname.match(/^\/v1\/devices\/([\w-]{8,64})\/presence$/);
    if (presenceMatch && request.method === "POST") {
      if (!state.devices[presenceMatch[1]]) return send(response, 404, { error: "unknown device" });
      const body = await readJson(request);
      const task = typeof body.task_id === "string" && body.task_id ? body.task_id.slice(0, 64) : null;
      const ttl = Math.min(Math.max(Number(body.ttl) || 90, 5), 180);
      if (task) presence.set(presenceMatch[1], { task, until: Date.now() + ttl * 1000 });
      else presence.delete(presenceMatch[1]);
      return send(response, 200, { ok: true });
    }
    if (match && request.method === "POST" && match[2]) {
      const device = state.devices[match[1]];
      if (!device) return send(response, 404, { error: "unknown device" });
      const status = await push(device, { kind: "test", title: "Forge", body: "Offline alerts are working." });
      return send(response, status < 300 ? 200 : 502, { status });
    }
    return send(response, 404, { error: "not found" });
  } catch (error) {
    return send(response, 400, { error: String(error.message || error) });
  }
}).listen(PORT, HOST, () => {
  log(`forge-relay on http://${HOST}:${PORT} → Kandev ${KANDEV_URL}`);
  log(`pairing key: ${state.pairingKey}  (state: ${STATE_FILE})`);
});

watch();
