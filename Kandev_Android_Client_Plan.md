# Kandev Native Android Client — Research & Implementation Plan

Research date: 29 October 2026. Target: an independent, open-source, full-featured Android client for a self-hosted Kandev instance, optimized first for one user's remote development workflow.

## Executive decision

Proceed with a Kotlin / Jetpack Compose / Material 3 Expressive client. Reuse Kandev's Go backend via HTTP and WebSocket interfaces, rather than reproducing orchestration. Build comprehensive feature parity in releases. Target v0.98.0 only for the initial client; the user will upgrade the server. Do not build legacy adapters, protocol negotiation, or a cross-version compatibility matrix. Do not rely on the external MCP API as the sole mobile integration, since it may not expose all web operations.

## Repository health and risks

- Canonical repository: https://github.com/kdlbs/kandev
- Latest release verified in research: v0.98.0 on 23 October 2026. Release archive shows frequent earlier 0.9x releases.
- Recent Git commits: 23 October worktree resume and GitHub workflow fixes; 24 October workspace MCP configuration addition. Strong evidence of active maintenance and several contributors.
- Maintainer/process quality is promising but not proven from release cadence alone. Before public launch, quantify 90-day commit activity, PR review latency, issue responsiveness, regression rate, security advisories and test coverage using a reproducible GitHub audit.
- Material concern: fast upstream changes and no verified stable public client SDK/API contract. Pin the target release and test its API contract. Older releases are outside scope.
- Security concern: Kandev documentation describes experimental auth (off by default), shared filesystem credentials, and WebSocket notification-routing limitations. Never expose an unauthenticated server directly to the public internet.

Primary evidence:
- https://github.com/kdlbs/kandev/releases
- https://github.com/kdlbs/kandev/commits
- https://github.com/kdlbs/kandev/blob/main/docs/ARCHITECTURE.md
- https://github.com/kdlbs/kandev/blob/main/docs/public/authentication.md
- https://kandev.ai/docs/mobile-remote-access

## Confirmed backend architecture

Kandev's Go process serves web assets, HTTP API, application WebSocket `/ws`, external MCP, and dedicated WebSocket routes for terminal and runtime features. The browser uses HTTP for domain actions and WebSockets for selected actions and live events. SQLite/PostgreSQL hold durable state; internal bus events are not replayed. Client reconnection must therefore re-fetch relevant resources, not depend on receiving missed socket events.

## Technical stack

- Android: Kotlin, Jetpack Compose, Material 3 Expressive; dynamic Material You color, accessible light/dark themes, adaptive layouts for phones/tablets/foldables.
- Presentation: MVVM or unidirectional state, Navigation Compose, lifecycle-aware Flow.
- Networking: OkHttp WebSocket, Retrofit/Ktor HTTP, Kotlin serialization, typed DTOs for the target release.
- Persistence: Room for offline cache and read/unread notifications, DataStore for settings, Android Keystore-backed credential protection.
- Background: WorkManager for opportunistic reconciliation; foreground WebSocket only while UI is in use. No promise of continuously running sockets while backgrounded.
- Diagnostics: opt-in redacted logs, reconnection metrics, compatible-server information.
- CI: Gradle, ktlint/detekt, unit tests, Compose UI tests, Android emulator tests, mock-server contract tests; release signing and reproducible builds.

## Functional scope: full feature parity target

1. Connection and identity: multiple server profiles, private Tailscale/HTTPS addresses, QR/deep-link pairing where safe, session/PAT authentication, certificate validation, account switching, target-release connection diagnostics.
2. Home: quick launch, pinned tasks, recent activity, running/waiting/completed overview, search, fast cached initial screen.
3. Workspaces/projects: create/configure workspaces, repository bindings, multi-repository task context, permissions/team controls where supported.
4. Kanban and task lifecycle: workflow states, task creation/edit/archive/delete, filters, saved views, task dependencies/subtasks, move operations, bulk actions where present.
5. Agent sessions: launch, stop, resume, prompt, planning, approvals, model/profile/executor selection, multiple sessions, histories and attachments.
6. Live conversation: streaming tool events, markdown/code rendering, reasoning display subject to provider capability, composer drafts, uploads, cross-session navigation and reconnection.
7. Development review: changed-file list, syntax-highlighted diffs, inline review, commits, branches, worktrees, pushes, PR create/status, repository status and conflicts.
8. Runtime tools: file browser/editor, terminal via dedicated socket, previews, plans/walkthroughs, canvases where enabled, task diagnostics.
9. Automation: workflow definitions, automation schedules/webhooks, run history, event triggers, approvals and manual gates.
10. Integrations: GitHub/GitLab, Jira, Linear, Sentry, Azure DevOps, plugins, external MCP settings as supported by backend and permissions.
11. Administration: agent profiles, executors, environments, prompts, secrets (never local persistent plaintext), system health, feature toggles, usage stats, storage and user management.
12. Mobile-specific: reliable notifications, deep links, widgets, notification action buttons, share-to-task, search shortcuts, offline last-known state and tablet split-pane layouts.

Each surface must be audited against the Kandev web frontend: inventory route/page/action, backing HTTP endpoint or WS event, required permissions, request/response schema, and test case. Mark features 'supported', 'backend addition required', or 'experimental' for the target release. Full parity is a roadmap goal, not a claim about existing API completeness.

## Notifications: recommended architecture

### Delivery modes

**A. While app is open:** receive WebSocket events, update UI and optionally display local Android notifications. Deduplicate against durable notification IDs.

**B. App backgrounded or terminated:** use Firebase Cloud Messaging (FCM). A small *opt-in server-side notification bridge* subscribes to Kandev's relevant lifecycle events (or consumes a proposed durable notification outbox), maps them to registered devices and sends minimal FCM payloads. FCM token belongs to an authenticated user/device and can be revoked. The app fetches full details securely after tap. Never include secrets, prompts, code, or terminal output in payloads.

**C. No Google services:** optional UnifiedPush-compatible distributor or user-hosted relay later; WorkManager periodic polling as a best-effort fallback only, not real-time. Self-hosted Kandev users must opt in to any third-party push infrastructure.

### Event catalog and controls

- Agent finished / failed / stalled
- Agent waiting for approval or user response (priority)
- Task moved to review or completed
- CI/PR update and merge conflict
- Automation failure / scheduled run complete
- Mention, assignment or explicit watch

Preferences: global and per workspace/task, quiet hours, critical vs routine notification channels, device selection, digest mode, deduplication, notification history and one-tap deep link. Actions such as Approve/Reject should never execute from an unauthenticated payload: fetch current approval state and require valid session, with a confirmation for high-risk actions.

### Reliability/security requirements

- Durable notification records/outbox with monotonic IDs and per-device acknowledgment; at-least-once delivery and client deduplication.
- Permission `POST_NOTIFICATIONS` on Android 13+; explain benefits before requesting.
- Device-token rotation, sign-out revocation, server-side target authorization, no leaking events across tenants/workspaces.
- Kandev auth and WS routing need independent security assessment before multi-user release.
- Do not assume permanent background WebSockets; Android background execution limits prohibit reliable always-on behavior.

Reference: https://developer.android.com/develop/ui/compose/notifications/notification-permission

## Suggested visual design

Use Material 3 Expressive, dynamic colors on Android 12+, high-contrast dark mode. Phone bottom destinations: Home, Tasks, Activity, More. Task screen uses top app bar, status chip, tabs for Chat / Changes / Files / Terminal / Plan; contextual bottom composer; floating task-create action. Tablet uses navigation rail and split-pane task/chat/diff. Respect reduced-motion settings, large fonts and accessibility touch targets. Persist scroll state and use skeletons only for genuinely uncached content.

## Performance objectives (proposed acceptance criteria)

- Warm start to cached home: under 1 second on a representative midrange Android device.
- Cold launch to usable cached home: target under 2 seconds; network refresh can follow asynchronously.
- Task switching with cached data: target under 300 ms perceived latency.
- Resilience to poor mobile networks and long-distance latency: optimistic controls only where reversible, event stream reconnect/backoff, explicit offline indication.
- No full workspace payload required just to display home; paging and incremental fetches.

Measure actual Kandev backend latency separately from Android rendering; do not attribute all slowness to the browser.

## Implementation phases and release gates

**Phase 0 — upstream audit (mandatory).** Pin a Kandev release; inspect web client network layer and Go route registrations, create API/action matrix; document auth/session/CSRF/origin/WebSocket protocol, and notification event availability for v0.98.0. Produce proof-of-connection and risk register.

**Phase 1 — native foundation.** Secure server connection, auth, Room cache, workspace/task lists, Material 3 navigation, live agent session and chat, basic agent actions. Release an internal alpha.

**Phase 2 — development parity.** Plans, approvals, files, diffs, Git and PRs, terminal, multiple sessions, resilient reconnect. Validate against Kandev web UI.

**Phase 3 — notification infrastructure.** Add durable server-side event delivery, registration/revocation, FCM bridge, notification preferences and deep-link handlers. Verify background/terminated reliability, duplicate suppression and no sensitive payloads.

**Phase 4 — advanced parity.** Workflow/automation controls, profiles, settings, integrations, plugin-dependent screens, multi-repository tasks, canvases, diagnostics and admin features.

**Phase 5 — public quality.** Contract tests against the target Kandev release; auth threat model, Android accessibility and tablet testing; signed releases, privacy disclosures, Play Store publishing and contributor documentation.

## Release/ownership decisions

- Keep Android repo separate (`kandev-android` concept); avoid an unmaintainable Kandev fork. Propose any missing stable APIs and notification outbox upstream first.
- Start as a personal self-hosted client but design connection profiles, account isolation and release automation for an eventual public open-source app.
- License selection must follow inspection of upstream licensing, client dependencies and any copied protocol definitions/assets.
- Do not run two agents on the exact same writable worktree simultaneously unless coordinated through Kandev.
- Initial target: Kandev v0.98.0 only. The user will upgrade the server. No older-server adapters, version-based fallbacks, or multi-version test infrastructure. Respect optional feature settings within the target release.

## Open questions to resolve in Phase 0

1. Exact REST endpoint and WebSocket action coverage for all current web screens, including approvals and terminal.
2. Does Kandev already persist notification records usable as a durable outbox? Web notification UI alone is not evidence of Android push capability.
3. Does current authentication support Android PAT + WS without browser-cookie assumptions? CSRF and Origin policies must be tested.
4. Are push gateway extensions/pluggable server modules supported without maintaining a fork?
5. What network path will the upgraded server use, and which actions are slow? The existing v0.96.0 server is outside the mobile client's target scope.
6. Intended notification transport: FCM default versus fully self-hosted preference.

## References

- Kandev repo: https://github.com/kdlbs/kandev
- Releases: https://github.com/kdlbs/kandev/releases
- Architecture: https://github.com/kdlbs/kandev/blob/main/docs/ARCHITECTURE.md
- Configuration: https://github.com/kdlbs/kandev/blob/main/docs/public/configuration.md
- Auth: https://github.com/kdlbs/kandev/blob/main/docs/public/authentication.md
- Mobile access: https://kandev.ai/docs/mobile-remote-access
- Android Material 3: https://developer.android.com/develop/ui/compose/designsystems/material3
- Android notification permission: https://developer.android.com/develop/ui/compose/notifications/notification-permission

## Evidence boundary

This is a repository-and-documentation feasibility study, not a line-by-line audited API contract or tested Android prototype. Phase 0 explicitly closes that gap before implementation estimates are committed.

## First-step discovery — 29 October 2026

Read-only inspection verified the following; this is an initial source audit, not completed compatibility testing.

- The running server's `GET /api/v1/system/info` reports `v0.96.0`, commit `f099a46d`. No update or restart was performed.
- GitHub's latest release is `v0.98.0`, published 23 October 2026. Upstream `main` was inspected in an isolated checkout at `012fb3c04069ba7154d9f5afdcddca49a8f3ba1c` (29 October). Use v0.98.0 as the implementation target. The main snapshot is research context only, not an additional test/support target.
- Upstream HTTP sources are `apps/web/lib/api/domains/`, notably `workspace-api.ts`, `kanban-api.ts`, `system-api.ts`, and `features-api.ts`. Application socket behavior is in `apps/web/lib/ws/client.ts` and the backend gateway. The live server also returns a JSON feature object from `GET /api/v1/features`.
- Current upstream supports conversation protocol selection (`v1` / `v2`). Its v2 path uses `session.conversation.subscribe` alongside `session.subscribe`, validating scope, session, epoch and revision. Do not assume a basic socket connection proves chat compatibility. Verify the protocol used by v0.98.0 and test subscription readiness, initial history/live-event ordering, deduplication and reconnect recovery for that release only. Do not implement multiple protocol paths.
- The existing UI enhancement is `a local kandev-personal checkout`, plugin `kandev-personal` v1.7.0, displayed as **Forge Personal**. Its manifest declares no data/event/write capabilities; it is a design reference, not a mobile API adapter. Its adjacent SDK checkout is older than current upstream and must not be treated as latest-source evidence.

### First implementation milestone: a native connection and conversation slice

1. Record the exact HTTP routes, DTOs, auth behavior, socket envelopes and subscription lifecycle needed for server information, features, workspaces, tasks, session history and live chat. Pin implementation evidence to v0.98.0. Limit the initial API inventory to this milestone; do not compare older releases.
2. Scaffold Kotlin/Compose with a small transport/repository boundary and a single implementation of the v0.98.0 API/protocol. No legacy adapters or version-based protocol selection.
3. Build server connection → workspace/task list → existing conversation with foreground live updates. Validate initial load and airplane-mode/resume recovery against v0.98.0, using an isolated instance until the user upgrades production. No v0.96.0 or main-snapshot test target.
4. Add sending a prompt after the read path and reconnect behavior pass, using a disposable test task/session. Later milestones add approvals, files/diffs, terminal and background notifications.

Acceptance: the native app opens a real conversation, receives new messages without duplicates, reloads missed durable state after disconnect, and reports connection/authentication errors clearly. Reading upstream source alone does not satisfy this gate.

### Adapt Forge Personal to Android

Use an explicit Compose light/dark color scheme: warm ivory `#f7f5f0`, terracotta primary `#a64b32`, charcoal `#1b1917`, and dark-theme peach primary `#e9a17e`. Use the plugin's full semantic palette when mapping Material roles. Keep this branded palette as the default; offer dynamic system colors as an optional theme.

Retain Hanken Grotesk for interface/chat and JetBrains Mono for code, with scalable Android `sp` sizes. Obtain Android-compatible TTF/OTF font assets and preserve licenses; the plugin currently ships web WOFF2 files. Adapt the anvil/spark logo and selected Lucide SVGs to Android vector assets with their licenses. Recreate compact task cards, solid panel dividers and readable chat in Compose; give interactive controls at least 48dp touch targets. CSS selectors, DOM branding hooks and the plugin's Linux binary do not transfer into a native app.

Source references: [upstream snapshot](https://github.com/kdlbs/kandev/tree/012fb3c04069ba7154d9f5afdcddca49a8f3ba1c), [v0.98.0 release](https://github.com/kdlbs/kandev/releases/tag/v0.98.0), [socket client at inspected snapshot](https://github.com/kdlbs/kandev/blob/012fb3c04069ba7154d9f5afdcddca49a8f3ba1c/apps/web/lib/ws/client.ts).


## Scaffold status — 29 October 2026

Implemented the Kotlin/Compose app foundation with Forge light/dark colors, bundled Hanken Grotesk and JetBrains Mono fonts, native vector branding, an in-memory server/token connection form, and a read-only workspace list with refresh/disconnect. HTTP DTOs and request paths are sourced from v0.98.0. No older-server adapter was added.

The initial implementation intentionally ends at workspaces. Task selection, conversations/WebSockets, persistence, and notifications remain future work. See README.md for build/install steps and docs/API_CONTRACT.md for scoped source evidence.

Live authentication/connectivity verification requires the user's planned server update to v0.98.0. No additional Kandev server was created. The current machine also lacks sufficient free disk for the installed emulator to launch; on-device visual verification remains outstanding.

Scaffold validation: Gradle wrapper debug build succeeded; all six HTTP unit tests passed; Android lint completed with zero errors and five advisory warnings. Font assets and licenses were verified inside the APK. Live server and emulator/on-device checks remain outstanding as described above.


## Live HTTP check after server update — 29 October 2026

The user upgraded production. Server info now reports v0.98.0 at the exact target commit `2d93502c256780e1f3d9df4348dafbd97b584188`. A temporary host-JVM harness exercised the app's existing compiled `KandevApi.connect()` against the live server and parsed both workspaces successfully. The instance currently has authentication disabled; no credentials were read or changed. PAT authentication and Android-to-server/UI checks remain unverified.

The emulator now passes disk-space checks but cannot access `/dev/kvm` as `opencode`. An administrator granting KVM access (with a refreshed login/session), or a physical Android device, is required for device testing. The next feature milestone remains workspace selection → task list → live conversation, using v0.98.0 only.


## Android emulator smoke check — 30 October 2026

Enabled KVM access for `opencode` with the user's authorized administrator credentials; no password was saved. An Android API 34 emulator ran with 2 cores and 1536 MiB guest RAM. After slow cold boot, a temporary System UI timeout and reducing the virtual display to 720×1560/320dpi, installed the existing debug APK and verified live v0.98.0 connection, both workspaces, refresh, disconnect, and light/dark rendering. Theme switching retained the connected state. The emulator was stopped after checks. The KVM and Android-to-server blockers are cleared. PAT authentication, network recovery, task/chat implementation, and physical-device checks remain future work.


## Task navigation and live text conversation — 30 October 2026

Implemented clickable workspaces, paged task lists, primary/default session selection and session switching, chronological paged chat history, selectable text with expandable tool/long-message cards, and a composer. The composer is disabled while the agent is starting/running. Requests use stable message identities for retrying identical unacknowledged drafts and never replay on reconnect; drafts clear only on acknowledgement. Native approvals/questions, rich Markdown rendering, attachments and session start/stop controls remain later scope.

The v0.98.0 v2 socket validates subscription identity and revisions, handles upsert/removal operations, batches reset-only invalidations into authoritative history refreshes on the same connection, and reloads previously requested history pages during recovery. Foreground lifecycle owns the socket; reconnection uses backoff and refetches durable state.

Validation passed: debug APK build, 16 unit tests, lint with zero errors/five existing advisory warnings. Emulator verified workspace → task → session/history, Live status, airplane-mode interruption/automatic reconnect, and background/resume. Production checks were read-only; prompt sending was tested with MockWebServer and no agent was started for validation. PAT-authenticated and physical-device checks remain outstanding.

## Rich workspace and offline persistence — 30 October 2026

Inspected the actual mobile web task view at 390×844, including Chat, Plan, Changes, Files and Terminal. Implemented five native navigation tabs using its content depth as reference: native Markdown plans/chat, virtualized collapsed tool groups, lazy shell output, working/branch changes and available diffs, workspace folders/text files, and hash-checked file edits. Terminal uses bundled xterm solely as an isolated local renderer; transport, reconnection, credentials and all other screens remain native.

Added private bounded atomic snapshots partitioned by server and credential, Keystore-encrypted profile restoration, cached navigation/history/panels/files, persisted chat submission identities and unsaved file edits. Network recovery wakes foreground sockets; PTYs reconnect to the same ID, never replay input and stop retrying when the terminal no longer exists. File saves omit upstream's optional overwrite fallback. Partial Git metadata updates preserve file membership. Cached drafts are bounded snapshots, not a guaranteed indefinite outbox.

Validation: 36 unit tests pass, including real git-apply patches, conflict retention, cache isolation/eviction/late-write retirement, authenticated profile encryption and terminal transport/reconnect behavior. Emulator verified live native plans/files, cache restoration after offline process death including an unsaved file edit, and network restoration. The test edit was discarded. A temporary owned terminal passed raw PTY replay and Android rendering with a harmless printf marker; no production prompt was sent.

Live Git refresh reports live_source_unavailable for this task environment; the app shows unavailable state without dropping saved data. Populated live diff verification requires a suitable environment. Native approvals/questions, attachments, session controls, Git mutations, background notifications and preview/status panels remain outstanding. No legacy adapters or extra Kandev instance were added.

Final recovery checks passed terminal airplane-mode reconnect, selected-PTY background/resume and offline process restart restoring cached chat. Temporary test terminal destruction was verified. The final APK was installed successfully; emulator/Gradle resources were stopped after validation. A retry of Git refresh still reported live_source_unavailable. Build/tests/lint results remain successful (zero lint errors; advisory warnings recorded in README).

## Native approvals and questions — 30 October 2026

Implemented pending-input banners and native response sheets. Approval review shows command/file details and exact offered choices, separating remembered decisions and cancellation. Structured questions wait for a complete bundle, respect allow_custom_text, retain per-bundle drafts and submit all answers explicitly. Durable turn scoping excludes lifecycle-only/completed turns and inactive sessions. Each submission revalidates current session/turn/message state; changed or expired requests do not mutate the server. No reconnect or cache read replays a response. Question resolution uses the server's full claimed/status/response envelope and displays the winning answer when another client answered first. Late acknowledgements cannot update another selected session. Ordinary chat submission is disabled while structured input is pending.

Validation: debug build, 57 unit tests and lint pass with zero errors (six existing advisory warnings). The isolated fixture/emulator flow verified approval choices, complete choice-only/free-text questions, persisted answers after offline process death, disabled offline submission, network restoration, one response per explicit action, and the competing-client winner. No production input request was answered or agent started for testing. Live pending-input submission, PAT and physical-device checks remain outstanding. Attachments and session lifecycle controls are the next feature candidates.


### Session controls and attachments — 30 October 2026

Implemented Start/Resume/Stop with fresh session-state checks and explicit actions. Added Android document selection, private attachment drafts, image/file delivery modes, streamed staged uploads, attachment-only messages, authenticated downloads, native image/text previews, Open with and Save a copy. Uploaded IDs and message identities survive uncertain delivery; reconnect does not automatically submit again.

Validation: debug build, all 72 unit tests and lint pass (zero errors; six advisory warnings). API 34 emulator checks passed lifecycle controls against an isolated fixture, two-file selection without automatic upload, offline process restart restoring attachments, attachment-only sending, authenticated image/text previews, offline cached preview and byte-exact Save a copy. Production sessions were not started/stopped and no production attachment was uploaded. The final lost-message-acknowledgement emulator scenario was interrupted and remains outstanding; unit tests cover upload retry identity and acknowledgement cleanup. PAT and physical-device checks remain outstanding, as does populated live Git diff validation.
