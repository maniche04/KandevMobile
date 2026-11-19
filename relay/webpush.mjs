// RFC 8291 Web Push message encryption (aes128gcm), shared by forge-relay and its test.
import crypto from "node:crypto";

const unb64u = (text) => Buffer.from(text, "base64url");
const hmac = (key, data) => crypto.createHmac("sha256", key).update(data).digest();

/** `keys` and `salt` are injectable only for the RFC test vector. */
export function encrypt(device, plaintext, { ecdh = null, salt = null } = {}) {
  const uaPublic = unb64u(device.p256dh);
  const authSecret = unb64u(device.auth);
  const sender = ecdh ?? crypto.createECDH("prime256v1");
  const asPublic = ecdh ? ecdh.getPublicKey() : sender.generateKeys();
  const shared = sender.computeSecret(uaPublic);
  const prkKey = hmac(authSecret, shared);
  const keyInfo = Buffer.concat([Buffer.from("WebPush: info\0"), uaPublic, asPublic, Buffer.from([1])]);
  const ikm = hmac(prkKey, keyInfo);
  salt ??= crypto.randomBytes(16);
  const prk = hmac(salt, ikm);
  const cek = hmac(prk, Buffer.from("Content-Encoding: aes128gcm\0\x01")).subarray(0, 16);
  const nonce = hmac(prk, Buffer.from("Content-Encoding: nonce\0\x01")).subarray(0, 12);
  const cipher = crypto.createCipheriv("aes-128-gcm", cek, nonce);
  const body = Buffer.concat([cipher.update(Buffer.concat([plaintext, Buffer.from([2])])), cipher.final(), cipher.getAuthTag()]);
  const header = Buffer.alloc(21);
  salt.copy(header, 0);
  header.writeUInt32BE(4096, 16);
  header.writeUInt8(asPublic.length, 20);
  return Buffer.concat([header, asPublic, body]);
}

