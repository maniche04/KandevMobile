// Direct FCM HTTP v1 delivery. Alert contents are AES-256-GCM encrypted per phone.
import crypto from 'node:crypto';

export function encryptAlert(key, alert) {
  alert = { ...alert };
  // Bound bytes rather than characters: emoji and non-Latin text expand in UTF-8.
  while (Buffer.byteLength(JSON.stringify({ v: 1, ...alert })) > 2650 && alert.body?.length) {
    alert.body = Array.from(alert.body).slice(0, -100).join('');
  }
  const iv = crypto.randomBytes(12);
  const cipher = crypto.createCipheriv('aes-256-gcm', Buffer.from(key, 'base64'), iv);
  const ciphertext = Buffer.concat([cipher.update(JSON.stringify({ v: 1, ...alert })), cipher.final(), cipher.getAuthTag()]);
  return { version: '1', iv: iv.toString('base64'), ciphertext: ciphertext.toString('base64') };
}

export function createFcm(credentials, request = fetch) {
  let cached, pending;
  async function accessToken() {
    if (cached?.expires > Date.now() + 60_000) return cached.token;
    if (pending) return pending;
    pending = (async () => {
      const now = Math.floor(Date.now() / 1000);
      const b64 = value => Buffer.from(JSON.stringify(value)).toString('base64url');
      const unsigned = `${b64({ alg: 'RS256', typ: 'JWT' })}.${b64({
        iss: credentials.client_email, scope: 'https://www.googleapis.com/auth/firebase.messaging',
        aud: 'https://oauth2.googleapis.com/token', iat: now, exp: now + 3600,
      })}`;
      const assertion = `${unsigned}.${crypto.sign('RSA-SHA256', Buffer.from(unsigned), credentials.private_key).toString('base64url')}`;
      const response = await request('https://oauth2.googleapis.com/token', {
        method: 'POST', body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion }),
        signal: AbortSignal.timeout(15_000),
      });
      if (!response.ok) throw new Error(`Firebase authentication failed (HTTP ${response.status})`);
      const result = await response.json();
      if (!result.access_token) throw new Error('Firebase did not return an access token');
      cached = { token: result.access_token, expires: Date.now() + result.expires_in * 1000 };
      return cached.token;
    })();
    try { return await pending; } finally { pending = null; }
  }
  return {
    accessToken,
    async send(device, alert) {
      const token = await accessToken();
      const message = { token: device.token, data: encryptAlert(device.encryption_key, alert),
        android: { priority: 'HIGH', ttl: '86400s' } };
      // FCM's 4096-byte limit includes the data keys and values; leave room for metadata.
      if (Buffer.byteLength(JSON.stringify(message.data)) > 3800) throw new Error('Encrypted alert exceeds FCM payload limit');
      const response = await request(`https://fcm.googleapis.com/v1/projects/${credentials.project_id}/messages:send`, {
        method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
        body: JSON.stringify({ message }), signal: AbortSignal.timeout(15_000),
      });
      if (response.status === 401) cached = null;
      const result = await response.json();
      const unregistered = result.error?.details?.some(detail => detail.errorCode === 'UNREGISTERED') === true;
      return { status: response.status, unregistered, error: result.error?.status };
    },
  };
}
