# Forge for Android

Forge is a native Android client for your own [Kandev](https://github.com/kdlbs/kandev) instance. Follow coding agents, review approvals, answer questions, edit plans and files, inspect diffs, and use a terminal from your phone.

**Compatibility:** Kandev **v0.98.0 or newer** (checked through v0.101.0); Android **8.0+** (API 26). Older Kandev versions are rejected. This is a community client, not an official Kandev or agent-provider app.

## Screenshots

These captures use synthetic example tasks, with no private server or conversation data.

| Tasks · light | Conversation · dark |
| --- | --- |
| ![Light task list with example coding tasks](docs/screenshots/tasks-light.png) | ![Dark conversation with an example coding agent](docs/screenshots/chat-dark.png) |

## At a glance

Forge has three tiers. **Only the core is required.** Each extra is independent, runs on infrastructure you own,
and can be added or removed later without reinstalling. Nothing connects to the author's servers or accounts.

| Tier | What you get | What it needs | If you skip it |
| --- | --- | --- | --- |
| **Core** (release APK) | Tasks, chat, approvals, questions, plans, diffs, files, terminal, offline cache, notifications while Forge can reach Kandev, in-app updates | Kandev v0.98.0+ and a network route from the phone (LAN, Tailscale or your own HTTPS) | — |
| **Recommended** · [usage strip](#recommended-provider-usage-plugin) | Codex/Claude/… quota chips on the home screen | Provider Usage **v0.12.0** plugin on the Kandev server | The strip is hidden; everything else works |
| **Optional** · [Cloudflare access](#optional-cloudflare-tunnel) | Reach Kandev from anywhere without a VPN app | Your Cloudflare account and domain, a Linux host, the included gateway | Use LAN, Tailscale or your own HTTPS |
| **Optional** · [away alerts (FCM)](#optional-away-alerts-fcm) | Alerts while the phone sleeps or can't reach Kandev | Your Firebase project, the included relay (Node.js 22+), and **your own APK build** | Alerts arrive only while Forge can reach Kandev |

## Quick start

1. **Check the server.** From a machine on the same network (add `-H "Authorization: Bearer …"` if Kandev requires auth):

   ```sh
   curl --fail http://192.168.1.10:38429/api/v1/system/info   # expect "version": "v0.98.0" or newer
   ```

2. **Install Forge.** Download `forge-<version>.apk` from [Releases](../../releases/latest), allow your browser or file
   manager to install apps, and open it. Release APKs offer updates from this repository's releases (Settings → About).
3. **Connect.** Enter the server's **base address** (e.g. `http://192.168.1.10:38429`, `http://devbox.tail1234.ts.net:38429`
   or `https://kandev.example.com`) — not `localhost`, and without `/api/v1` or `/ws`. Add the API token if required,
   tap **Connect** and choose a workspace.
4. **Optional:** install the [usage plugin](#recommended-provider-usage-plugin) on the server.

That's the whole core setup. No Firebase, Cloudflare, domain, relay, signing key or build tools are needed for it.

Plain HTTP is accepted only for private hosts (RFC 1918, Tailscale `100.64.0.0/10` and `*.ts.net`, `*.local`,
single-label names); public hosts need HTTPS. Each release lists the APK's SHA-256 and signing-certificate fingerprint
and carries a build attestation: `gh attestation verify forge-<version>.apk --repo maniche04/KandevMobile`.

## Reaching Kandev from outside your network

The phone needs a route to Kandev wherever you are. Pick one; you can save several in Settings → **Connection routes**
and Forge switches between them automatically.

| Route | Best for | Setup |
| --- | --- | --- |
| LAN | Home/office only | Enter the private address |
| **Tailscale** (simplest remote option) | Personal use from anywhere | Install Tailscale on the phone and the server, enter the server's tailnet address |
| Your own HTTPS | You already run a reverse proxy | Authenticated HTTPS endpoint that forwards WebSockets; enter it with the Kandev token |
| Cloudflare Tunnel + Access | No VPN app on the phone, your own domain | [Included gateway](#optional-cloudflare-tunnel) |

Alternate routes must reach the same server (same version and workspace UUID). Switching routes never replays writes.
See [route details](remote/README.md#automatic-routes).

## Recommended: Provider Usage plugin

The home screen's quota strip reads the server-side
[Provider Usage plugin](https://github.com/kdlbs/kandev-plugin-provider-usage), which uses CodexBar on the Kandev host.
Install the **v0.12.0** release (Kandev → Settings → Plugins → Install plugin → Upload the
[v0.12.0 release](https://github.com/kdlbs/kandev-plugin-provider-usage/releases/tag/v0.12.0)). Don't install the
plugin's unreleased `main` branch; Forge hasn't been tested with it. Sign providers in on the server, never on the phone. Full steps,
verification and troubleshooting: [usage setup guide](docs/OPTIONAL_INTEGRATIONS.md#optional-provider-usage-setup).

## Optional: Cloudflare Tunnel

Use this if you want access from anywhere without Tailscale. The included gateway puts Kandev behind your own Cloudflare
Tunnel and Access application, with one service token per phone and a separate gateway token. Phones are enrolled by
importing a connection file (browser login challenges are not supported). No Cloudflare credentials are embedded in the
APK. Cloudflare terminates TLS and can see app traffic. Setup, enrollment and revocation: [remote/README.md](remote/README.md).

## Optional: away alerts (FCM)

Without this, Forge notifies you whenever it can reach Kandev (it runs a foreground monitor while agents work). For
alerts while the phone sleeps or is off your network, you need push through Firebase Cloud Messaging:

```
Kandev → forge-relay (your host) → your Firebase project → Forge (your build)
```

1. Create a Firebase project and register package `ca.mslab.forge` (and/or `ca.mslab.forge.debug`).
2. Put its `google-services.json` in `app/` and [build and sign your own APK](#build-from-source). The release APK
   can't do this: Firebase configuration is compiled in, and published releases deliberately have none.
3. Run the included [forge-relay](relay/README.md) with that project's service account on a host that can reach Kandev.
4. Pair the phone in Settings → **Alerts when away** over LAN or Tailscale.

The phone needs Google Play services. Alert contents are encrypted per phone before they reach Firebase. Your build is
signed with your key, so it can't update over a release install: uninstall the release first (saved profiles and cache
are cleared), and publish your own updates (see [In-app updates](#in-app-updates)).

## Build from source

Needed only for FCM, your own signing key, or development: JDK 17, Android SDK Platform 36 and Build Tools 36.0.0
(the Gradle wrapper is included).

```sh
# Point this at your installed Android SDK, or set sdk.dir in local.properties.
export ANDROID_HOME="$HOME/Android/Sdk"
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

No Firebase file or signing key is required to build. Debug installs use package `ca.mslab.forge.debug`; release
installs use `ca.mslab.forge`. They have separate app data and may coexist. Debug builds are slower; assess performance
on a release build. Signed release builds: [Release builds and signing](#release-builds-and-signing). The full
dependency map is in [docs/OPTIONAL_INTEGRATIONS.md](docs/OPTIONAL_INTEGRATIONS.md).

## Using Forge

- **Home:** workspace picker, search, workflow/repository lists or Kanban board, and a **Needs you** group for open requests. Provider usage chips appear when the [usage plugin](#recommended-provider-usage-plugin) is installed.
- **Task:** agent/session picker, live chat, tool output, attachments, approvals and complete question bundles. Start, stop, resume and queue messages explicitly.
- **Workspace tabs:** editable Markdown plans, changes with commit/push/PR actions, files with conflict-aware saves, and a terminal. Terminal input and uncertain submissions are never automatically replayed.
- **Offline:** cached tasks, history and drafts remain available. Reconnecting revalidates state. Approval submission checks the current request to avoid answering expired requests.
- **Settings:** routes, notification permissions, remote enrollment, away-alert pairing and disconnect. Disconnect clears local connection/cache data; revoke remote credentials separately on the server.

Connection profiles are AES-GCM encrypted using an Android Keystore key. The local database contains cached conversation content; protect the device accordingly.

## Release builds and signing

Without signing configuration, `./gradlew :app:assembleRelease` produces `app/build/outputs/apk/release/app-release-unsigned.apk`. To install or distribute a release, create your own key and store its configuration outside the checkout:

```sh
mkdir -p "$HOME/.forge"
chmod 700 "$HOME/.forge"
keytool -genkeypair -keystore "$HOME/.forge/release.jks" \
  -alias forge -keyalg RSA -keysize 3072 -validity 10000
```

Create `~/.forge/signing.properties` (absolute `storeFile` path; no shell expansion):

```properties
storeFile=/absolute/path/to/.forge/release.jks
storePassword=YOUR_STORE_PASSWORD
keyAlias=forge
keyPassword=YOUR_KEY_PASSWORD
```

```sh
chmod 600 "$HOME/.forge/signing.properties" "$HOME/.forge/release.jks"
./gradlew :app:assembleRelease
adb install -r app/build/outputs/apk/release/app-release.apk
```

Keep the key backed up privately. App updates require the same package and signing key; an APK signed by another operator cannot update your existing install, so switching between a published release and your own build means uninstalling first (which clears saved profiles and cache). Firebase configuration is optional for both variants; see [away alerts](#optional-away-alerts-fcm).

### In-app updates

Settings › About › App updates reads a download directory holding a signed APK and `ca.mslab.forge.json` (version,
SHA-256, optional notes). No update server of your own is needed:

- **Published releases** are built with this repository's `https://github.com/OWNER/REPOSITORY/releases/latest/download/`
  as the update address, so they update from GitHub Releases.
- **Your fork's releases:** add the signing secrets below and push a tag; your APKs then update from your fork.
- **Your own server (optional):** `scripts/publish-apk.sh DIRECTORY` copies the signed release APK there and writes the
  manifest last. Serve the directory over HTTP(S) and set it in Settings, or at build time:

```properties
# ~/.forge/update.properties (never committed); FORGE_UPDATE_URL in the environment takes precedence
url=https://downloads.example.com/forge/
```

A source build with no address leaves the field empty for you to fill in or ignore.

The phone downloads the APK named in the manifest, checks its SHA-256 and hands it to Android's installer. Android
asks before each install and only accepts an APK signed with the same key. Bump `versionCode` for each release.

### Publishing GitHub releases

`.github/workflows/release.yml` builds, tests, signs and attests the APK when a tag `v<versionName>` is pushed, then
creates the release with the APK and update manifest. Before the first tag, store your key as repository secrets
(read from files; never paste them into chats or commit them):

```sh
base64 -w0 "$HOME/.forge/release.jks" | gh secret set FORGE_KEYSTORE_BASE64
gh secret set FORGE_KEYSTORE_PASSWORD   # prompts; or pipe from a private file
gh secret set FORGE_KEY_ALIAS
gh secret set FORGE_KEY_PASSWORD
# Optional: refuse to publish an APK signed by any other key.
gh variable set FORGE_CERT_SHA256 --body "$(keytool -list -v -keystore "$HOME/.forge/release.jks" -alias forge | sed -n 's/.*SHA256: //p')"
```

Then bump `versionCode` and `versionName` in `app/build.gradle.kts`, commit, and `git tag v0.4.0 && git push --tags`.
Avoid `\` in key passwords used by the workflow; the generated properties file does not escape it.

## Development and AI-assisted setup

### For AI agents setting up Forge

Facts to rely on:

- **Required:** a Kandev **v0.98.0 or newer** server and a network route from the phone. Installing the release APK and entering
  the base address is the complete core setup; no build, Firebase, Cloudflare, relay or plugin is needed.
- **Recommended, server-side only:** Provider Usage plugin **v0.12.0** release (not unreleased `main`). Without it the usage strip is hidden.
- **Optional, independent, operator-owned:** Cloudflare Tunnel ([remote/README.md](remote/README.md)) and FCM away
  alerts ([relay/README.md](relay/README.md)). FCM always means building and signing the operator's own APK with their
  own `google-services.json`; a release APK can never receive FCM alerts.
- The full dependency map, with what happens when each piece is absent, is in
  [docs/OPTIONAL_INTEGRATIONS.md](docs/OPTIONAL_INTEGRATIONS.md). The pinned protocol is in
  [docs/API_CONTRACT.md](docs/API_CONTRACT.md).

Rules:

- Ask the user for their server base URL, whether auth is enabled, and how the phone reaches the server. Every address,
  hostname and account in this repository is a placeholder; never reuse one.
- Start with the core setup. Add Cloudflare or FCM only when the user asks for it.
- Keep tokens, keystores, service accounts and enrollment files outside the repository.
- Verify with read-only requests (`/api/v1/system/info`). Never submit prompts, answer approvals or start/stop agent
  sessions as a setup test.
- [AGENTS.md](AGENTS.md) covers build commands and this maintainer's host limits (heap caps, no local emulator);
  substitute your own test device.

A setup request you can give an assistant:

> Read README.md and follow its "For AI agents" section. Help me get Forge running against my Kandev server (v0.98.0 or newer).
> Ask me for my server address, auth and network route; set up only the core unless I ask for extras.

Verification without an emulator:

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
node --test relay/*.test.mjs remote/*.test.mjs
```

Instrumented checks require a connected Android device. See [screenshot capture instructions](docs/screenshots/README.md). Local build heaps are capped; keep those limits on resource-constrained hosts.

Source map:

| Path | Purpose |
| --- | --- |
| `app/src/main/java/ca/mslab/forge/data` | API, models, encrypted profile, Room cache, sync and routing |
| `app/src/main/java/ca/mslab/forge/ui` | Jetpack Compose screens |
| `app/src/main/java/ca/mslab/forge/notify`, `push` | Live notifications, relay pairing and FCM |
| `remote/` | Optional Cloudflare provisioning and authenticated origin gateway |
| `relay/` | Optional Node.js notification service |

[API contract](docs/API_CONTRACT.md) records the pinned protocol. [UX notes](docs/UX_REVIEW.md) and [historical plan](Kandev_Android_Client_Plan.md) describe development decisions, not deployment requirements. PAT authentication and physical-device behavior have not yet had full end-to-end validation; see [publication checklist](docs/PUBLISHING.md).

## Attribution

Forge is released under the [MIT License](LICENSE). Bundled fonts, terminal assets, Markwon and Lucide retain their own notices in [licenses/](licenses/). Agent-provider marks belong to their respective owners and identify integrations. Report security issues privately as described in [SECURITY.md](SECURITY.md).
