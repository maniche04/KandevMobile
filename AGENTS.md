# Forge / KandevMobile — agent notes

Read README.md first. Forge targets the Kandev v0.98.0 protocol and accepts newer releases; see docs/API_CONTRACT.md.
Never assume the maintainer's server, hostname, tokens, Firebase project or Cloudflare account
are available to another operator. Example addresses are placeholders.

## Build and validation

Use JDK 17 and Android SDK 36. Set JAVA_HOME and ANDROID_HOME for your installation.

```sh
./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
node --test relay/*.test.mjs remote/*.test.mjs
```

Keep Gradle and Kotlin heap caps. On the maintainer's constrained build host, user-level limits
are Gradle 2048 MiB, Kotlin 1536 MiB, and two workers. Do not raise them or add project worker
overrides. Do not stop other users' build processes.

## Android device policy

**Never launch a local emulator on the maintainer's build host.** Builds and daemons can
exhaust its RAM. Use the shared remote emulator supplied by the operator or a physical device.
Do not invent an endpoint. If the required device is unreachable, stop device work and report it.
Plain builds and unit/lint checks do not require a device.

Before any instrumentation run, connect to the supplied endpoint and confirm it is `device`,
not `offline`. Set ANDROID_SERIAL when more than one target is present:

```sh
adb connect "$ANDROID_SERIAL"  # for a remote TCP endpoint supplied by the operator
adb devices
```

Public screenshots must use synthetic data. The capture test requires a fresh debug install
with no saved profile. Do not clear another user's device or connect screenshot fixtures to
production. See docs/screenshots/README.md.

## Changes and deployment

- Keep prompts, approvals and lifecycle actions explicit. Never replay uncertain writes or terminal input.
- Never submit prompts, answer approvals, edit files on a live server or start/stop real sessions as a setup smoke test.
- Keep keys, PATs, relay state, Cloudflare management credentials and phone enrollments outside the repository.
- Cloudflare/FCM are optional. Preserve builds without google-services.json and without signing configuration.
- Unit templates need operator-selected ExecStart paths and private environment files.
- Do not publish this checkout's historical commits: old fixtures contain private conversations.
  Follow docs/PUBLISHING.md to create a reviewed snapshot without history.
