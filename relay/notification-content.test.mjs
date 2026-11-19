import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { requestText, notificationBody, notificationIdentity } from './notification-content.mjs';

test('relay and Android use identical request previews', () => {
  const fixtures = JSON.parse(fs.readFileSync(new URL('../app/src/test/resources/notification-previews.json', import.meta.url)));
  for (const fixture of fixtures) assert.equal(requestText(fixture.pending, fixture.message), fixture.expected);
});

test('history does not replace an open request or an agent reply', () => {
  const messages = [
    { type: 'permission_request', metadata: { status: 'approved', action_details: { command: 'old command' } } },
    { type: 'clarification_request', metadata: { status: 'pending', question: { prompt: 'Use staging?' } } },
    { author_type: 'agent', type: 'message', content: 'Updated the notification layout.' },
  ];
  assert.equal(notificationBody('needs_you', 'question', messages), 'Question: Use staging?');
  assert.equal(notificationBody('turn_finished', null, messages), 'Updated the notification layout.');
  assert.equal(notificationBody('failed', null, [], 'Build failed'), 'Build failed');
  assert.equal(notificationBody('turn_finished', null, []), 'Finished its turn');
});

test('encrypted payload includes the exact model identity, not an inferred display name', () => {
  const task = { primary_agent_profile_id: 'profile-1', primary_agent_name: 'Luna 6' };
  assert.deepEqual(notificationIdentity(task, [{ name: 'codex-acp', profiles: [{ id: 'profile-1', model: 'gpt-6-luna' }] }]),
    { agent: 'Luna 6', agent_profile_id: 'profile-1', model: 'gpt-6-luna', agent_type: 'codex-acp' });
  assert.equal(notificationIdentity(task, []).agent, 'Luna 6');
});
