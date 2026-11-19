// These previews intentionally match Android's NotificationContent.kt.
export function requestText(pending, message) {
  const details = message?.metadata?.action_details;
  if (message?.type === 'clarification_request') return message.metadata?.question?.prompt
    ? `Question: ${message.metadata.question.prompt}` : 'A question is waiting for you';
  if (details?.command != null) return `Approve running:\n${details.command}`;
  if (details?.path != null) return `Approve access to:\n${details.path}`;
  return pending === 'permission' ? 'An action needs your approval' : 'A question is waiting for you';
}

export function notificationBody(kind, pending, messages, error) {
  if (kind === 'needs_you') {
    const message = messages.find(m => (m.type === 'permission_request' &&
      (m.requests_input || ['pending', 'requested', 'waiting', ''].includes(m.metadata?.status ?? 'pending'))) ||
      (m.type === 'clarification_request' && (!m.metadata?.status || m.metadata.status === 'pending')));
    return requestText(pending, message);
  }
  if (kind === 'failed') return error?.trim() || 'The agent stopped with an error';
  return messages.find(m => m.author_type === 'agent' && ['message', 'content'].includes(m.type) && m.content?.trim())?.content.trim()
    || 'Finished its turn';
}

export function notificationIdentity(task, agents) {
  for (const agent of agents) {
    const profile = agent.profiles?.find(profile => profile.id === task.primary_agent_profile_id);
    if (profile) return { agent: task.primary_agent_name || profile.name || null,
      agent_profile_id: profile.id, model: profile.model || null, agent_type: agent.name };
  }
  return { agent: task.primary_agent_name || null, agent_profile_id: task.primary_agent_profile_id || null };
}
