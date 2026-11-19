# Forge Android — UX review, web comparison and plan

30 October 2026. Compared against the live Kandev v0.98.0 mobile web client (Chrome on the API 34 emulator, 1080×2340) and the web source in `kandev-personal-source/apps/web` (an Oct 3 snapshot, so treat its details as hints). Server behavior marked **verified** was checked against the live v0.98.0 instance.

## 1. Why a native app at all

The web client is already feature-rich. A native app only earns its place if it does three things the web can't do well:

1. **Instant.** Every screen paints from a local database in under one frame. The network only revalidates. Opening a task you looked at yesterday, or one that's currently running, should never show a spinner.
2. **Present when you're not looking.** Notifications when an agent finishes a turn or needs you, plus a persistent "live" notification showing what running agents are doing. Tapping either jumps straight into the task.
3. **Phone-first input.** Reply, approve and queue follow-ups in two taps, including from the notification itself.

Everything below is judged against those three goals.

## 2. What the web mobile client has

| Area | Mobile web (v0.98) | Forge Android today |
|---|---|---|
| Home | Kanban by **workflow step** (In Progress / Review…), step switcher, FAB **New task**, hamburger menu | Flat list of first 50 tasks, filter chips |
| Task navigation | Menu sheet: workspace switch, search, **tasks grouped by repository**, **subtasks nested under parents**, relative time, diff stats, saved views/filters | No grouping, no parent/child, 50-task cap (server has 78) |
| Task create / move / archive | Yes (dialog, step move, archive, context menu) | No |
| Task top bar | Title ▾ opens **task switcher sheet**; second line `owner/repo · ⎇ branch · +20571 −5379`; Approve button when in review | Back + title + state; no switcher |
| Session bar | Agent logo, profile name, **2/2** session count, dropdown: switch, **new session**, set primary, stop/resume/delete | Icon menu, switch only |
| Chat layout | Flat, full-width agent text (no bubbles), dense; per-message actions: copy, raw, metadata, favorite, **prev/next turn**; time and model tag | Bubbles and cards; larger, less dense |
| Tool calls | One line `>_ command ✓`, inline collapsible **Output · 209 B**, grouped "3 tool calls" | Similar, but output needs a separate fetch tap |
| Activity | "Starting Claude ⋮⋮" live activity line, todo list | State pill only |
| Composer | **Queues instructions while the agent runs** ("Queue instructions to the agent…"), plan-mode toggle, MCP/context, permission mode (**Manual**), **model picker**, **context-window ring**, stop | Send disabled while running; no plan mode, model or context |
| Above composer | Blocker chip (dependencies), split view, scroll to bottom, stats, share | None |
| Bottom nav | Chat, Plan, Changes (**badge**), Files, Terminal, Panels, Status | Chat, Plan, Changes, Files, Terminal |
| Changes | Commits list, **Push / Pull / PR**, **Walkthrough**, review mode with **0/111 reviewed**, diff sheet | Working tree / branch lists, diff view |
| Plan | Editable, comments, revisions, "implementation started" | Read-only |
| Files | Search (name and content), create/rename/delete, view at ref | Browse, view, edit |
| Terminal | Multiple user shells (create/park/rename/destroy), keybar | Similar |
| Status | Connection sheet | Header line (good) |
| Notifications | `session.turn_finished`, `session.clarification_requested`, sound; suppressed when viewing that task | None |
| Extras | Quick chat, automations, integrations (GitHub/GitLab/Jira), port forwarding, preview | — (out of scope for now) |

## 3. Problems with the current Android UI (including my first polish pass)

- **Not compact.** Task cards are 6–8 lines tall; about 3 fit on screen. The web fits 6+. Chat bubbles waste horizontal space on a phone, and code blocks wrap badly inside them.
- **Wrong primary structure.** Kandev is organized by *workflow step* and *repository*, with *subtasks*. A recency-sorted flat list loses that. Most important are the tasks that need you, and those aren't surfaced first.
- **"Your turn" is noise.** Kandev marks every idle session `WAITING_FOR_INPUT`. The real signal is `pending_action` (permission/question) and `task_pending_action`, which arrive in `task.status_summary.updated` (**verified**).
- **The composer blocks while the agent runs.** The web queues follow-ups; we disable Send.
- **Too many controls in the task header.** A Live pill, a refresh, a big Stop button and a sessions icon take two rows above the chat.
- **Not actually snappy.** Every navigation hits the network first or shows a spinner. The cache is a JSON-file snapshot store keyed per screen, not a queryable database. The task list polls HTTP instead of listening to the workspace event stream.
- **Missing everyday actions:** new task, new session, model/plan mode, queue, approve-review, push/PR, archive.

## 4. Data and performance architecture

**Room (SQLite) as the single source of truth; the UI only observes database Flows.**

- Tables: `workspace`, `repository`, `workflow_step`, `task` (including status summary, pending action, git stats, parent id), `session`, `message` (indexed by session and created time, with turn id), `turn`, `todo`, `plan`, `git_status`, `file_tree`, `draft`, `queued_message` (mirror of the server queue, never replayed locally).
- **One connection, owned by a service, not by screens.** A single WebSocket per profile:
  - `user.subscribe` for workspace-wide `task.status_summary.updated`, `task.updated/created/deleted`, `session.state_changed` (includes `context_window`), `session.pending_action_changed`, `session.turn_finished`, `session.clarification_requested` (**verified** the first four arrive)
  - `session.conversation.subscribe` for the focused session (the existing v2 projection logic stays)
  - `session.todos_updated` and `session.activity_changed` for progress

  Events are written to the database; screens re-render from it. This replaces the per-screen sockets and HTTP polling.
- **Stale-while-revalidate everywhere:** open a screen, show database rows immediately, revalidate in the background with a subtle top progress line, never a full-screen spinner when data exists.
- **Prefetch:** whenever a task is running, needs you, or was opened recently, keep its latest message page and plan warm in the database, so tapping a notification opens instantly, even offline.
- **Keep the current safety rules:** prompts, approvals and lifecycle actions are explicit and never replayed on reconnect; drafts and submission identity persist; file saves check the original hash.
- **Startup:** release build with R8, a baseline profile, and markdown pre-rendered off the main thread (already partly done). Target: cold start to first task row under 400 ms on a mid-range phone.

## 5. Notifications design

Three channels:

1. **Agents live (ongoing, low importance, silent).** A foreground service runs while any agent is running or waiting on you. One summary notification ("2 agents working · 1 needs you"), plus one grouped child per active task:
   - title
   - current activity ("Running `git status`", "Thinking…", "Generating")
   - **todo progress bar** from `session.todos_updated` (e.g. 3/7)
   - elapsed time

   Tap opens the task. Actions: *Stop*, *Open*. The service stops itself after a short idle grace period. Setting: *While agents run* (default) / *Always* / *Off*.
2. **Needs you (high importance, heads-up).** Approval or question.
   - Approval notification: expanded text shows the command and path; actions *Approve once* and *Deny* (re-validated against the live request before sending, as the in-app sheet already does), plus *Open*.
   - Question notification: *Open* only, since questions need the form.
3. **Turn finished (default importance).** Last agent message as BigText, with a **Reply** action (inline RemoteInput sends `message.add`, or queues if the agent is busy) and *Open*.

Rules:
- Suppress for the task currently on screen, as the web does.
- Collapse repeats per task.
- Deep links preserve back stack: Tasks → Task → Chat.

Platform notes:
- Android 14+ needs a foreground-service type. Use `specialUse` (personal sideload, no Play review) or `remoteMessaging`; `dataSync` is time-limited on Android 15.
- Android 16 "Live Updates" (`Notification.ProgressStyle`, promoted ongoing) gives the lock-screen and status-bar chip experience. It needs compileSdk 36 and degrades to normal progress notifications on older versions.
- Notifications only arrive while the service holds the socket. True push when the app is fully closed would need a relay (UnifiedPush/FCM), which is a later, opt-in decision.
- Battery: one idle WebSocket with 20–30 s pings is cheap; the service is not running when no agents are active.

## 6. Proposed UI (compact)

### Home: "Tasks"
- **Top bar (56dp):** workspace name ▾ (switcher sheet), connection dot, search, overflow (Settings).
- **Sections, collapsible, counts in headers:**
  1. **Needs you**: pending approval/question, failed or blocked.
  2. **Running**: live activity line and todo progress.
  3. **Review**.
  4. **Other steps** (by workflow step order).
  5. **Done** (collapsed, lazy-loaded).
- Toggle **Group by: Step / Repository**.
- **Subtasks** indented under their parent with a ↳ and a collapse chevron.
- **Row (2 lines, ~64dp):**
  - Line 1: state glyph, title, time on the right.
  - Line 2: muted `repo · ⎇ branch · Opus 5.5 · +41k −10k`, plus a red dot for errors.
  - No description in the row; long-press shows a preview sheet.
- **Swipe:** right = archive/move step, left = stop/resume. **FAB:** New task.
- Pagination loads all tasks progressively from the database; no "Load more" button.

### Task screen
- **Top bar, two lines:** back, title ▾ (task switcher sheet, recent and active tasks), `repo · ⎇ branch · +a −d`. Right side: one overflow (Stop/Resume, sessions, archive, open in browser).
- **Session strip (36dp):** agent avatar, profile/model, `1/2` sessions ▾, state dot and activity text ("Running tests…"). Live status is the dot colour, so no separate pill.
- **Chat:**
  - Agent text full-width without bubbles. User messages as compact right-aligned blocks.
  - Tool calls as one-line rows with inline expandable output, grouped by turn.
  - Turn separators with duration.
  - Long-press a message for copy / raw / share.
  - Floating scroll-to-bottom chip with an unread count.
- **Todo strip:** collapsible "3/7 · Writing tests" above the composer while the agent works.
- **Composer:**
  - Single line growing to 6.
  - Left: attach.
  - Right: send, which queues when busy (shows a "Queued · 2" chip with edit/cancel).
  - A second row is revealed on focus: plan-mode toggle, model, permission mode, context ring.
- **Bottom nav (56dp):** Chat, Plan, Changes (badge = files changed), Files, Terminal.

### Changes
- Commits section, working tree, branch diff.
- Per-file *reviewed* checkbox with "x/y reviewed".
- Push / Pull / Create PR buttons with confirmation.
- Diff in a full-screen sheet with prev/next file.

### Visual language
- Dense type scale: 14 sp body, 12 sp meta, 13 sp mono. 4/8 dp spacing grid. Hairline dividers instead of cards in lists.
- Colour carries status only. Keep the Forge ivory/terracotta palette and the dark theme; follow the system theme with a manual override.
- Motion: shared-axis transitions, 150 ms.

## 7. Feature roadmap (each phase ships a usable APK)

1. **Foundation: speed.** Room database, a single connection service with `user.subscribe`, Flow-driven screens, stale-while-revalidate, prefetch, R8 and baseline profile. No visible feature change except "everything is instant".
2. **Compact UI.** New home (sections, grouping, subtasks, two-line rows), task top bar and switcher, session strip, flat chat, todo strip, badges. Fixes the noisy "Your turn" signal using `pending_action`.
3. **Notifications.** Live foreground-service notifications with todo progress, needs-you heads-up with Approve/Deny, turn-finished with inline Reply, deep links, settings.
4. **Composer power.** Message queue (`message.queue.*`), plan mode, model and permission-mode picker, context ring, new session, set primary.
5. **Task management.** New task (repo, branch, agent profile, executor, step), move step, archive, dependencies/blockers.
6. **Code review.** Commits, push/pull/PR, per-file review progress, walkthroughs, plan editing and comments, file search.

Each phase gets unit tests for the data layer, emulator screenshots in light and dark, and a measured cold start / task-open time.

## 8. Decisions (30 October 2026)

- **Speed first.** A Room database is the source of truth. One app-wide socket (`user.subscribe` plus running sessions) writes events into it, and hot conversations are prefetched. Release builds are R8-optimized: 2.4 MB, versus 14.8 MB for debug.
- **Home:** workflow-step grouping by default, with a repository toggle; a pinned "Needs you" section; two-line rows; nested subtasks.
- **Live notifications run only while agents are active.** They use Android 16 Live Updates where available.
- **Approvals always open the app.** Turn-finished alerts allow inline Reply.
- **Offline alerts:** forge-relay on the server sends RFC 8291-encrypted Web Push via UnifiedPush through an operator-configured UnifiedPush-enabled ntfy instance. Forge, not ntfy, renders the notification. Direct FCM remains an optional addition.

## 9. Original open questions


1. **Home default grouping:** by workflow step (like the web kanban) or by repository (like the web menu)?
2. **Live notification:** run only while agents are active (recommended), or always connected?
3. **Approve from the notification:** allowed for "approve once", or must approvals always open the app?
4. **Android 16 Live Updates:** is your phone on Android 16 (if so, move to compileSdk 36)?
5. **Order:** start with Phase 1 (speed) or Phase 2 (compact UI) first? Both are needed; Phase 1 first avoids building the new UI twice.

## 10. Incident during this review

While checking the web UI, one tap meant to open the session picker hit **Walkthrough** on the Phase H-b task. That sent `@changes-walkthrough` to its idle session at 04:54:25 UTC. The agent produced a 29-step walkthrough saved on that task and returned to idle; no code changes and nothing is pending. All further web inspection was done from source, read-only.
