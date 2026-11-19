import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { enrollmentDownload } from './enrollment-download.mjs';
const token = 'a'.repeat(43);
async function run(callback, clock = Date.now) {
  const server = enrollmentDownload({ token_sha256: createHash('sha256').update(token).digest('hex'), expires: Date.now() + 60000, content: '{"private":"test-only"}' }, clock);
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  try { await callback(`http://127.0.0.1:${server.address().port}/forge-enroll/${token}`); }
  finally { await new Promise(resolve => server.close(resolve)); }
}
test('previews and HEAD cannot consume or disclose the connection', () => run(async url => {
  for (const method of ['GET', 'GET', 'HEAD']) {
    const res = await fetch(url, { method }); assert.equal(res.status, 200);
    assert(!(await res.text()).includes('test-only'));
    assert.equal(res.headers.get('cache-control'), 'no-store, max-age=0');
  }
  const download = await fetch(url, { method: 'POST' });
  assert.equal(download.status, 200); assert((await download.text()).includes('test-only'));
  assert.equal(download.headers.get('content-disposition'), 'attachment; filename="forge-phone.json"');
  assert.equal((await fetch(url, { method: 'POST' })).status, 410);
}));
test('concurrent downloads disclose only one copy', () => run(async url => {
  const results = await Promise.all([fetch(url, { method: 'POST' }), fetch(url, { method: 'POST' })]);
  assert.deepEqual(results.map(r => r.status).sort(), [200, 410]);
}));
test('unknown paths and methods never return credentials', () => run(async url => {
  assert.equal((await fetch(url + '/api/v1/tasks')).status, 404);
  assert.equal((await fetch(url.replace(token, 'b'.repeat(43)), { method: 'POST' })).status, 404);
  assert.equal((await fetch(url, { method: 'PUT' })).status, 405);
}));
test('expired tickets deny downloads', () => run(async url => {
  assert.equal((await fetch(url, { method: 'POST' })).status, 410);
}, () => Date.now() + 120000));
