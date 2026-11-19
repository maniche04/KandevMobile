# Initial HTTP contract

Target: [Kandev v0.98.0](https://github.com/kdlbs/kandev/releases/tag/v0.98.0), commit `2d93502c256780e1f3d9df4348dafbd97b584188`. The newer main snapshot inspected during discovery is not the implementation authority. No older-server adapter or protocol negotiation is planned.

| Operation | Request | Consumed response fields |
| --- | --- | --- |
| Server info | `GET /api/v1/system/info` | `version`, `commit` |
| Workspaces | `GET /api/v1/workspaces` | `workspaces[]` with `id`, `name`, optional nullable `description`; `total` |

Both send `Accept: application/json`. A supplied PAT is sent as `Authorization: Bearer <PAT>`. No cookies, browser bootstrap payload, query-string credentials or external MCP are required by this slice. The app displays a server-update requirement if the server's version, with an optional leading `v` removed, is not exactly `0.98.0`; there is no alternate execution path. Extra JSON fields are ignored. Required fields retain strict decoding.

Source evidence in the pinned release:

- [System HTTP client](https://github.com/kdlbs/kandev/blob/2d93502c256780e1f3d9df4348dafbd97b584188/apps/web/lib/api/domains/system-api.ts) and [SystemInfo type](https://github.com/kdlbs/kandev/blob/2d93502c256780e1f3d9df4348dafbd97b584188/apps/web/lib/types/system.ts).
- [Workspace HTTP client](https://github.com/kdlbs/kandev/blob/2d93502c256780e1f3d9df4348dafbd97b584188/apps/web/lib/api/domains/workspace-api.ts), [HTTP types](https://github.com/kdlbs/kandev/blob/2d93502c256780e1f3d9df4348dafbd97b584188/apps/web/lib/types/http.ts), and [Go route/handler](https://github.com/kdlbs/kandev/blob/2d93502c256780e1f3d9df4348dafbd97b584188/apps/backend/internal/task/handlers/workspace_handlers.go).
- [Bearer authentication middleware](https://github.com/kdlbs/kandev/blob/2d93502c256780e1f3d9df4348dafbd97b584188/apps/backend/internal/auth/httpmw/middleware.go).

The unit fixtures project these source types onto only the fields the scaffold consumes. Source inspection and fixture tests do not replace actual authentication and connectivity testing. Verify both when the user's instance is updated, before extending the transport into chat.


## Task and conversation slice

| Operation | Request | Consumed response fields |
| --- | --- | --- |
| Task list | `GET /api/v1/workspaces/{id}/tasks?page=N&page_size=50` | `tasks[]`: `id`, `title`, `state`, optional `primary_session_id`; `total` |
| Task sessions | `GET /api/v1/tasks/{id}/sessions` | `sessions[]`: `id`, `name`, `state` |
| History | `GET /api/v1/task-sessions/{id}/messages?limit=100&sort=desc[&before=cursor]` | `messages[]`, `has_more`, `cursor` |

History's `cursor` is the last row's message ID in the returned page. IDs and query values are URL encoded. Typed tool metadata drives grouped activity and lazy shell-output loading.

The app opens `/ws` with the bearer token in the handshake header when supplied. Requests carry a unique `id`, `type: request`, `action`, and `payload`; responses/errors correlate by `id`. It sends `session.subscribe` for state and `session.conversation.subscribe` with `consumer_kind: core`, a unique `scope_id`, and the selected `session_id`. The acknowledgement must confirm protocol 2 and matching scope/session, with epoch and decimal revision. No alternative protocol is negotiated.

`session.conversation.changed` contains scope/session, epoch, base/revision strings, optional reset/check/terminal controls, and message/turn operations. Message upserts replace rows by ID and timestamp; removals delete rows. Gaps, epoch changes, malformed deltas, and reset-only invalidations coalesce into authoritative history reloads with a fresh subscription watermark. In production, reset-only invalidations can carry empty revisions; these are recovery signals, not ordinary deltas. Check controls with equal revisions are heartbeats. Other scopes/sessions are ignored. A terminal control stops the stream.

Sending uses `message.add` with `task_id`, `session_id`, `content`, `message_id`, and `client_message_id`. The same message identity is retained for retrying identical unacknowledged content. The backend can dispatch/resume agent work; this is never invoked by read-only live smoke tests. The client does not replay pending requests after disconnect, and clears a draft only after a correlated acknowledgement.

Pinned source evidence: [session HTTP client](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/api/domains/session-api.ts), [task HTTP client](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/api/domains/kanban-api.ts), [conversation payloads](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/types/session-events.ts), [WebSocket client](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/ws/client.ts), and [message admission handler](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/task/handlers/message_handlers.go).

## Rich workspace and persistence

The mobile web at 390×844 was inspected directly for Chat, Plan, Changes, Files and Terminal. Native panels consume the pinned release contracts:

| Operation | Transport / action | Notes |
| --- | --- | --- |
| Plan | WS `task.plan.get` with `task_id` | Direct plan or empty object; read-only UI |
| Workspace tree | WS `workspace.tree.get` | `session_id`, relative `path`, `depth: 1`; response `root` |
| Read file | WS `workspace.file.get` | Plain UTF-8 `content`, `size`, optional `is_binary`; **not base64** |
| Save file | WS `workspace.file.update` | Relative path, original SHA-256, whole-file unified patch; headers have no a/b prefix for `git apply -p0` |
| Changes | WS `session.focus`, `session.git.refresh`, `session.cumulative_diff` | Working status comes through `session.git.event` / `status_update`; cumulative files are under `cumulative_diff.files` |
| Shell output | HTTP `GET /api/v1/task-sessions/{id}/messages/{messageId}/shell-output` | On-demand tool output |
| Terminal list | HTTP `GET /api/v1/tasks/{id}/terminals?task_environment_id=...` | Environment read from session metadata |
| New terminal | WS `user_shell.create` | Explicit user action only; `task_id` and `task_environment_id`; response `terminal_id` |
| PTY | WebSocket `/terminal/environment/{environmentId}?terminalId=...` | Bearer header, raw binary UTF-8 input/output; resize is byte `0x01` followed by UTF-8 JSON `{cols,rows}` |

File saves deliberately omit `desired_content`: upstream uses that optional field to overwrite a file when the original hash differs. Without it, conflicts retain the local edit. Patch generation is tested against actual `git apply -p0 --unidiff-zero`, including Unicode, CRLF, empty files and missing final newlines.

Sources: [workspace file RPCs](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/ws/workspace-files.ts), [Git handlers](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/agent/handlers/git_handlers.go), [terminal hook](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/components/task/use-passthrough-terminal.ts). All behavior targets this single release.

A private per-profile snapshot store partitions by normalized origin and token digest, writes atomically and applies size limits. Credentials use Android Keystore AES-GCM. Cached files and chat are ordinary private app files, protected by Android's application sandbox. Forgetting a profile retires its store so late asynchronous writes cannot recreate it. No network mutation or terminal input is automatically replayed from disk.

## Native approvals and questions

The client loads `GET /api/v1/task-sessions/{sessionId}/turns` alongside history. Input controls require an input-capable session and the newest open durable conversational turn. Open turns outrank completed turns, then started/created timestamps and ID decide order. Lifecycle-only turns are excluded only for the four upstream encodings `true`, `1`, `"true"`, `"1"`. Cached requests can be reviewed offline; submission requires a live connection and fresh session/turn/message revalidation.

Permission messages carry `request_id`, `pending_id`, offered `options` and `action_details`. `permission.respond` sends task/session/request/pending identity, the selected offered `option_id`, and explicit `cancelled`/`rejected` flags. Remembered choices remain separate buttons. A denial with no offered reject uses cancellation without inventing an option. Codex cancellation choices likewise omit `option_id`. Known stale permission causes disable the request, while raw server diagnostics remain hidden.

Clarification messages share `pending_id` and have per-question `question`, `question_index`, `question_total`, status and optional response. The whole bundle must arrive before submission. `allow_custom_text: false` suppresses free text. Every question needs an answer; text and skip reasons are capped at 2,000 Unicode code points. Answers are posted to `POST /api/v1/clarification/{pendingId}/respond` as `{answers, rejected:false}`; skipping posts `{rejected:true,reject_reason}` without answers. This HTTP mutation has connection-failure retries disabled.

A successful response must supply the release's full `success`, `claimed`, `status`, `response` envelope. `claimed:false` displays and applies the server's winning answer, never the losing client's submission. `409 code:not_active` and missing requests disable stale controls. Other failures keep local answers for an explicit retry. No reconnect, process restoration, or draft-cache read sends a response automatically. Late acknowledgements cannot affect another selected session.

Pinned sources: [permission response semantics](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/permissions/respond.ts), [permission WS handler](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/orchestrator/handlers/handlers.go), [clarification wire types](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/clarification/protocol/types.go), [HTTP resolution handler](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/clarification/handlers.go), [turn scope rules](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/utils/pending-clarification.ts), and [validation](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/clarification/outcome_validation.go).

## Session lifecycle and attachments

Existing sessions use `session.stop` with `{session_id}` or `session.launch` with `{task_id,session_id,intent,activation_source:"user_action"}`. CREATED uses `start_created`; COMPLETED/FAILED/CANCELLED use `resume`. RUNNING/STARTING/WAITING_FOR_INPUT allow Stop, unless `cancellation_pending` is true. The selected session is reloaded immediately before mutation. Queued/suppressed launch acknowledgements are displayed without another launch. Uncertain actions require a state refresh before explicit retry. Navigation and reconnect never launch or stop an agent. Start uses the existing session settings and task brief; the composer draft is sent separately with Send.

Attachments use the release's staged-byte API, independent of any Kandev UI download-size limit:

- `POST /api/v1/attachments`, multipart fields `workspace_id`, `kind`, `delivery_mode`, `mime_type`, and raw `file`. Mutating HTTP retries are disabled. The response is a staged descriptor with `attachment_id`, name/MIME/kind/mode, `size_bytes`, state and expiry.
- `message.add.attachments[]` carries `{type,attachment_id,mime_type,name,size_bytes,delivery_mode}`. File-only messages have empty content. Successfully uploaded IDs and the message fingerprint/identity are stored before admission, so an explicit retry can reuse them after a lost acknowledgement.
- `GET /api/v1/attachments/{id}/content` streams authenticated bytes into an atomic private cache file; credentials never enter URLs or external viewer intents. Inline data exposed by this same release's message DTO can be decoded into a bounded private preview file.

Limits are 10 attachments and 100 MiB total per message. Images supported as prompt content are PNG/JPEG/GIF/WebP; other files default to path delivery. Files selected through Android's document picker are copied to bounded private storage, preserving drafts across process death without depending on long-lived URI grants. Upload happens only on explicit Send; reconnect and restoration never upload or submit. Local copies are removed after message acknowledgement. Unused remote staged uploads expire server-side, which also avoids deleting a file that may have been claimed during an uncertain admission. Expired uploads can be removed and reattached after checking the conversation.

Only selected draft bytes and downloaded preview bytes are exposed through a non-exported FileProvider with per-intent read grants. Native image decoding is sampled to a maximum 2048-pixel dimension; text previews are limited to 200 KB. Other content opens through an Android app or the document Save a copy flow. Preview bytes are separate from the JSON snapshot budget and may be evicted.

Pinned evidence: [lifecycle actions](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/hooks/domains/session/use-session-actions.ts), [state eligibility](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/hooks/domains/session/session-action-state.ts), [launch contract](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/services/session-launch-service.ts), [attachment client](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/web/lib/api/domains/attachment-api.ts), [attachment handlers](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/task/handlers/attachment_handlers.go), [shared limits](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/messageconstraints/attachments.go), and [staged lifecycle](https://github.com/kdlbs/kandev/blob/v0.98.0/apps/backend/internal/task/service/attachment_service.go). Native picker/sharing follow [Android documents](https://developer.android.com/training/data-storage/shared/documents-files) and [FileProvider](https://developer.android.com/training/secure-file-sharing/setup-sharing) guidance.
