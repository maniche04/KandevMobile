import assert from 'node:assert/strict';
import crypto from 'node:crypto';
import fs from 'node:fs';
import { test } from 'node:test';
import { createFcm, encryptAlert } from './fcm.mjs';
const fixture = JSON.parse(fs.readFileSync(new URL('../app/src/test/resources/fcm-envelope.json', import.meta.url)));
function decrypt(data) {
  const raw = Buffer.from(data.ciphertext, 'base64');
  const cipher = crypto.createDecipheriv('aes-256-gcm', Buffer.from(fixture.key, 'base64'), Buffer.from(data.iv, 'base64'));
  cipher.setAuthTag(raw.subarray(-16));
  return JSON.parse(Buffer.concat([cipher.update(raw.subarray(0, -16)), cipher.final()]));
}
test('encrypted alert fixture and UTF-8 payload limits', () => {
  assert.deepEqual(decrypt(fixture.envelope), { v: 1, ...fixture.alert });
  const data = encryptAlert(fixture.key, { ...fixture.alert, body: '🌏'.repeat(1500) });
  assert.ok(Buffer.byteLength(JSON.stringify(data)) < 3800);
  assert.equal(decrypt(data).kind, 'test');
  assert.notEqual(encryptAlert(fixture.key, fixture.alert).iv, fixture.envelope.iv);
});
const { privateKey, publicKey } = crypto.generateKeyPairSync('rsa', { modulusLength: 2048 });
const credentials = { project_id: 'test-project', client_email: 'test@example.com', private_key: privateKey.export({ type: 'pkcs8', format: 'pem' }) };
test('JWT authentication, cached token, encrypted data, and stale-token classification', async () => {
  let authCalls = 0, sends = 0;
  const client = createFcm(credentials, async (url, options) => {
    if (url.includes('oauth2')) {
      authCalls++;
      const parts = options.body.get('assertion').split('.');
      assert.ok(crypto.verify('RSA-SHA256', Buffer.from(parts.slice(0,2).join('.')), publicKey, Buffer.from(parts[2], 'base64url')));
      const claims = JSON.parse(Buffer.from(parts[1], 'base64url'));
      assert.equal(claims.scope, 'https://www.googleapis.com/auth/firebase.messaging');
      return Response.json({ access_token: 'test-token', expires_in: 3600 });
    }
    assert.equal(url, 'https://fcm.googleapis.com/v1/projects/test-project/messages:send');
    assert.equal(options.headers.Authorization, 'Bearer test-token');
    const { message } = JSON.parse(options.body);
    assert.equal(message.android.priority, 'HIGH');
    assert.equal(message.notification, undefined);
    assert.deepEqual(decrypt(message.data), { v: 1, ...fixture.alert });
    sends++;
    return sends === 1 ? Response.json({ name: 'message/1' })
      : Response.json({ error: { status: 'NOT_FOUND', details: [{ errorCode: 'UNREGISTERED' }] } }, { status: 404 });
  });
  const device = { token: 'device-token', encryption_key: fixture.key };
  assert.equal((await client.send(device, fixture.alert)).status, 200);
  assert.equal((await client.send(device, fixture.alert)).unregistered, true);
  assert.equal(authCalls, 1);
});
test('credential errors surface without including credentials', async () => {
  const client = createFcm(credentials, async () => new Response('private detail', { status: 403 }));
  await assert.rejects(client.accessToken(), /Firebase authentication failed \(HTTP 403\)/);
});

test('relay registration preserves legacy devices and validates FCM configuration', async () => {
  const { spawn } = await import('node:child_process');
  const os = await import('node:os');
  const path = await import('node:path');
  const net = await import('node:net');
  const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'forge-fcm-test-'));
  const reservation = net.createServer();
  await new Promise(resolve => reservation.listen(0, '127.0.0.1', resolve));
  const port = reservation.address().port;
  await new Promise(resolve => reservation.close(resolve));
  const child = spawn(process.execPath, ['relay/forge-relay.mjs'], { env: {
    ...process.env, RELAY_STATE: temp, RELAY_HOST: '127.0.0.1', RELAY_PORT: String(port),
    KANDEV_URL: 'http://127.0.0.1:1', GOOGLE_APPLICATION_CREDENTIALS: '',
  }, stdio: ['ignore', 'pipe', 'pipe'] });
  try {
    await new Promise((resolve, reject) => {
      const timer = setTimeout(() => reject(new Error('Relay did not start')), 5000);
      child.stdout.on('data', data => { if (String(data).includes('pairing key:')) { clearTimeout(timer); resolve(); } });
      child.on('error', reject);
    });
    const key = JSON.parse(fs.readFileSync(path.join(temp, 'state.json'))).pairingKey;
    const send = (device, auth = key) => fetch(`http://127.0.0.1:${port}/v1/devices`, {
      method: 'POST', headers: { Authorization: `Bearer ${auth}`, 'Content-Type': 'application/json' }, body: JSON.stringify(device),
    });
    const legacy = { id: 'legacy-device-1', endpoint: 'https://example.com/push', p256dh: Buffer.alloc(65).toString('base64url'), auth: Buffer.alloc(16).toString('base64url') };
    assert.equal((await send(legacy)).status, 200);
    const device = { id: 'fcm-device-1', transport: 'fcm', token: 'valid-looking-fcm-token-123', encryption_key: fixture.key };
    assert.equal((await send(device, 'wrong-key')).status, 401);
    assert.equal((await send({ ...device, encryption_key: 'bad' })).status, 400);
    assert.equal((await send(device)).status, 503);
    const saved = JSON.parse(fs.readFileSync(path.join(temp, 'state.json')));
    assert.ok(saved.devices[legacy.id]);
    assert.equal(saved.devices[device.id], undefined);
    // The phone checks its own registration, reports the task on screen, and unpairs.
    const call = (method, suffix, body) => fetch(`http://127.0.0.1:${port}/v1/devices/${suffix}`, {
      method, headers: { Authorization: `Bearer ${key}`, 'Content-Type': 'application/json' }, body: body && JSON.stringify(body),
    });
    const check = await call('GET', legacy.id);
    assert.equal(check.status, 200);
    assert.equal((await check.json()).transport, 'webpush');
    assert.equal((await call('GET', device.id)).status, 404);
    assert.equal((await call('POST', `${legacy.id}/presence`, { task_id: 'task-1', ttl: 60 })).status, 200);
    assert.equal((await call('POST', `${device.id}/presence`, { task_id: 'task-1' })).status, 404);
    assert.equal((await call('DELETE', legacy.id)).status, 200);
    assert.equal((await call('GET', legacy.id)).status, 404);
  } finally {
    const exited = new Promise(resolve => child.on('exit', resolve));
    child.kill(); await exited;
    fs.rmSync(temp, { recursive: true, force: true });
  }
});
