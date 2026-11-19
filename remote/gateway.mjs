import http from 'node:http';
import net from 'node:net';
import { readFileSync } from 'node:fs';
import { createHash } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export function authorized(request, state) {
  const authorization = request.headers.authorization ?? '';
  if (!/^Bearer [A-Za-z0-9_-]{43,}$/.test(authorization)) return false;
  const hash = createHash('sha256').update(authorization.slice(7)).digest('hex');
  return state.devices.some(device => device.token_sha256 === hash && device.expires > Date.now());
}
// Read-only plugin telemetry the app shows above the task list; every other plugin route stays private.
const PLUGIN_READS = new Set(['/api/plugins/kandev-provider-usage/webhooks/providers']);
export function allowedPath(url, method = 'GET') {
  // Reject ambiguous paths and leave administrative/internal listeners private.
  if (!url || !url.startsWith('/') || /[\\\x00-\x20]/.test(url)) return false;
  const path = url.split('?')[0];
  if (/%(?:2f|5c|2e)/i.test(path) || path.split('/').some(s => s === '..' || s === '.')) return false;
  if (PLUGIN_READS.has(path)) return method === 'GET';
  return path === '/ws' || path.startsWith('/api/v1/') || path.startsWith('/terminal/environment/');
}
function headers(request, origin) {
  const result = { ...request.headers, host: origin.host };
  for (const name of Object.keys(result)) {
    if (name.startsWith('cf-') || name.startsWith('x-forwarded-') || name === 'forwarded' || name === 'authorization' || name === 'cookie') delete result[name];
  }
  // A future authenticated Kandev origin can have its PAT supplied locally; never expose it to clients.
  if (process.env.FORGE_ORIGIN_TOKEN_FILE) result.authorization = `Bearer ${readFileSync(process.env.FORGE_ORIGIN_TOKEN_FILE, 'utf8').trim()}`;
  return result;
}
export function gateway(stateFile, originUrl = process.env.FORGE_ORIGIN_URL || 'http://127.0.0.1:38429') {
  const origin = new URL(originUrl);
  if (origin.protocol !== 'http:' || origin.pathname !== '/' || origin.search || origin.hash || origin.username || origin.password) {
    throw new Error('FORGE_ORIGIN_URL must be an HTTP origin without a path or credentials. Use a trusted private link to Kandev.');
  }
  const originPort = Number(origin.port || 80);
  const admit = req => {
    try { return allowedPath(req.url, req.method) && authorized(req, JSON.parse(readFileSync(stateFile, 'utf8'))); }
    catch { return false; } // Missing/corrupt state closes the gateway.
  };
  const server = http.createServer((req, res) => {
    if (!admit(req)) { res.writeHead(401, { 'Cache-Control': 'no-store' }); res.end('Device authentication required.'); return; }
    const upstream = http.request({ hostname: origin.hostname, port: originPort, method: req.method, path: req.url, headers: headers(req, origin) }, response => {
      const h = { ...response.headers, 'Cache-Control': 'no-store' };
      delete h['set-cookie'];
      res.writeHead(response.statusCode, h); response.pipe(res);
    });
    upstream.on('error', () => { if (!res.headersSent) res.writeHead(502); res.end(); });
    req.on('aborted', () => upstream.destroy());
    res.on('close', () => upstream.destroy());
    req.pipe(upstream);
  });
  server.on('upgrade', (req, socket, head) => {
    if (!admit(req) || req.headers.upgrade?.toLowerCase() !== 'websocket') { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); return; }
    const upstream = net.connect(originPort, origin.hostname, () => {
      const h = headers(req, origin);
      upstream.write(`${req.method} ${req.url} HTTP/${req.httpVersion}\r\n${Object.entries(h).map(([k,v]) => `${k}: ${v}`).join('\r\n')}\r\n\r\n`);
      if (head.length) upstream.write(head);
      upstream.pipe(socket); socket.pipe(upstream);
    });
    upstream.on('error', () => socket.destroy()); socket.on('error', () => upstream.destroy());
    socket.on('close', () => upstream.destroy()); upstream.on('close', () => socket.destroy());
  });
  return server;
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  gateway(process.env.FORGE_REMOTE_STATE).listen(38431, '127.0.0.1', () => console.log('Forge remote gateway listening on loopback:38431'));
}
