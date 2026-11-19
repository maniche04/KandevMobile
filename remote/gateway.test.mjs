import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import net from 'node:net';
import { authorized, allowedPath, gateway } from './gateway.mjs';
const token = 'a'.repeat(43);
const device = { token_sha256: createHash('sha256').update(token).digest('hex'), expires: Date.now() + 60000 };
test('tokens must match a currently approved device', () => {
  assert(authorized({ headers: { authorization: `Bearer ${token}` } }, { devices: [device] }));
  assert(!authorized({ headers: { authorization: `Bearer ${'b'.repeat(43)}` } }, { devices: [device] }));
  assert(!authorized({ headers: { authorization: `Bearer ${token}` } }, { devices: [{ ...device, expires: 0 }] }));
  assert(!authorized({ headers: {} }, { devices: [device] }));
});
test('only unambiguous app paths reach Kandev', () => {
  for (const path of ['/api/v1/system/info', '/ws', '/terminal/environment/x/ws?cols=80']) assert(allowedPath(path), path);
  for (const path of ['/', '//evil.com', '/api/v1/../admin', '/api/v1/%2e%2e/admin', '/api/v1/%2fadmin', '/api/v1/\\admin']) assert(!allowedPath(path), path);
});
test('only the provider usage read is exposed among plugin routes', () => {
  const usage = '/api/plugins/kandev-provider-usage/webhooks/providers';
  assert(allowedPath(usage, 'GET')); assert(allowedPath(`${usage}?refresh=1`, 'GET'));
  assert(!allowedPath(usage, 'POST'));
  for (const path of ['/api/plugins', '/api/plugins/install', '/api/plugins/kandev-provider-usage/webhooks/update',
    '/api/plugins/kandev-provider-usage/webhooks/status', '/api/plugins/kandev-provider-usage/config',
    '/api/plugins/kandev-provider-usage/webhooks/providers/../update']) assert(!allowedPath(path, 'GET'), path);
});
test('missing approval state fails closed over HTTP and WebSocket', async () => {
  const server = gateway('/nonexistent/forge-state.json').listen(0, '127.0.0.1');
  await new Promise(resolve => server.on('listening', resolve));
  try {
    const response = await fetch(`http://127.0.0.1:${server.address().port}/api/v1/system/info`, { headers: { Authorization: `Bearer ${token}` } });
    assert.equal(response.status, 401);
    const upgrade = await new Promise((resolve, reject) => {
      const socket = net.connect(server.address().port, '127.0.0.1', () => {
        socket.write(`GET /ws HTTP/1.1\r\nHost: localhost\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nAuthorization: Bearer ${token}\r\n\r\n`);
      });
      socket.setTimeout(3000, () => { socket.destroy(); reject(new Error('Upgrade rejection timed out')); });
      socket.on('data', data => { socket.destroy(); resolve(data.toString()); });
      socket.on('error', reject);
    });
    assert.match(upgrade, /^HTTP\/1.1 401 Unauthorized/);
  } finally { await new Promise(resolve => server.close(resolve)); }
});

test('a configured origin receives HTTP and WebSocket traffic with scoped headers', async () => {
  const { createServer } = await import('node:http');
  const { mkdtempSync, writeFileSync, rmSync } = await import('node:fs');
  const { tmpdir } = await import('node:os');
  const { join } = await import('node:path');
  const directory = mkdtempSync(join(tmpdir(), 'forge-gateway-'));
  const state = join(directory, 'devices.json');
  writeFileSync(state, JSON.stringify({ devices: [device] }));
  const received = [];
  const origin = createServer((req, res) => {
    received.push(req.headers);
    res.end('example origin');
  }).listen(0, '127.0.0.1');
  await new Promise(resolve => origin.on('listening', resolve));
  origin.on('upgrade', (req, socket) => {
    received.push(req.headers);
    socket.end('HTTP/1.1 101 Switching Protocols\r\nConnection: Upgrade\r\nUpgrade: websocket\r\n\r\n');
  });
  const url = `http://127.0.0.1:${origin.address().port}`;
  const server = gateway(state, url).listen(0, '127.0.0.1');
  await new Promise(resolve => server.on('listening', resolve));
  const base = `http://127.0.0.1:${server.address().port}`;
  try {
    const response = await fetch(`${base}/api/v1/system/info`, {
      headers: { Authorization: `Bearer ${token}`, 'CF-Access-Client-Secret': 'example-secret', Cookie: 'example-cookie' }
    });
    assert.equal(response.status, 200);
    assert.equal(await response.text(), 'example origin');
    const upgrade = await new Promise((resolve, reject) => {
      const socket = net.connect(server.address().port, '127.0.0.1', () => {
        socket.write(`GET /ws HTTP/1.1\r\nHost: example.invalid\r\nConnection: Upgrade\r\nUpgrade: websocket\r\nAuthorization: Bearer ${token}\r\n\r\n`);
      });
      socket.setTimeout(3000, () => { socket.destroy(); reject(new Error('Upgrade timed out')); });
      socket.on('data', data => { socket.destroy(); resolve(data.toString()); });
      socket.on('error', reject);
    });
    assert.match(upgrade, /^HTTP\/1.1 101/);
    assert.equal(received.length, 2);
    for (const headers of received) {
      assert.equal(headers.host, new URL(url).host);
      assert.equal(headers.authorization, undefined);
      assert.equal(headers['cf-access-client-secret'], undefined);
      assert.equal(headers.cookie, undefined);
    }
  } finally {
    await new Promise(resolve => server.close(resolve));
    await new Promise(resolve => origin.close(resolve));
    rmSync(directory, { recursive: true, force: true });
  }
});

test('origin configuration rejects paths, embedded credentials and unsupported protocols', () => {
  for (const url of ['https://example.com', 'http://user:password@example.com', 'http://example.com/api', 'http://example.com?key=value']) {
    assert.throws(() => gateway('/unused', url), /FORGE_ORIGIN_URL/);
  }
});
