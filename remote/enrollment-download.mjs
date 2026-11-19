import http from 'node:http';
import { readFileSync } from 'node:fs';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';

/** A temporary bootstrap download: no proxying, no filesystem paths from requests, no request logs. */
export function enrollmentDownload(ticket, clock = Date.now) {
  let used = false;
  const expected = Buffer.from(ticket.token_sha256, 'hex');
  return http.createServer((req, res) => {
    const candidate = /^\/forge-enroll\/([A-Za-z0-9_-]{43})$/.exec(req.url ?? '')?.[1];
    const digest = createHash('sha256').update(candidate ?? '').digest();
    const valid = candidate && expected.length === digest.length && timingSafeEqual(expected, digest);
    res.setHeader('Cache-Control', 'no-store, max-age=0');
    res.setHeader('Referrer-Policy', 'no-referrer');
    res.setHeader('X-Robots-Tag', 'noindex, nofollow, noarchive');
    res.setHeader('X-Content-Type-Options', 'nosniff');
    res.setHeader('Content-Security-Policy', "default-src 'none'; form-action 'self'; frame-ancestors 'none'");
    if (!valid) { res.writeHead(404); res.end('Download not found.'); return; }
    if (used || clock() >= ticket.expires) { res.writeHead(410); res.end('This download has expired or has already been used.'); return; }
    if (req.method === 'GET' || req.method === 'HEAD') {
      res.setHeader('Content-Type', 'text/html; charset=utf-8');
      res.end(req.method === 'HEAD' ? '' : '<!doctype html><html lang="en"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>Forge connection</title><h1>Forge connection</h1><p>Save this private connection file, then import it in Forge under Settings → Secure remote access.</p><form method="post"><button type="submit">Download forge-phone.json</button></form><p>This download works once and expires shortly. Delete the downloaded copy after importing.</p></html>');
      return;
    }
    if (req.method !== 'POST') { res.writeHead(405, { Allow: 'GET, HEAD, POST' }); res.end(); return; }
    used = true; // Commit before writing bytes; concurrent requests cannot obtain another copy.
    req.resume();
    res.setHeader('Content-Type', 'application/json');
    res.setHeader('Content-Disposition', 'attachment; filename="forge-phone.json"');
    res.end(ticket.content);
  });
}
if (import.meta.url === pathToFileURL(process.argv[1]).href) {
  const ticket = JSON.parse(readFileSync(process.env.FORGE_ENROLLMENT_TICKET, 'utf8'));
  enrollmentDownload(ticket).listen(38434, '127.0.0.1', () => console.log('Temporary Forge enrollment download ready on loopback.'));
}
