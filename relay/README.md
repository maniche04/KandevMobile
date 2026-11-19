# forge-relay

Agent alerts for Forge that reach your phone even when Kandev doesn't: Tailscale off, away from home, phone asleep.

```
Kandev → forge-relay → Firebase Cloud Messaging → Forge
                     (encrypted alert contents)
```

- Watches Kandev's workspace feed and alerts on transitions only:
  - **needs you**: an approval or question that is still open after a few seconds; auto-approved permissions don't alert.
  - **turn finished**: includes the agent's last message.
  - **failed**: includes the error preview.
- Direct FCM alerts are encrypted with a per-phone AES-256-GCM key. Firebase sees ciphertext, not task titles or messages. Existing UnifiedPush devices continue to receive Web Push.
- Legacy Web Push payloads are end-to-end encrypted ([RFC 8291](https://www.rfc-editor.org/rfc/rfc8291), checked against the RFC test vector in `webpush.test.mjs`) and signed with VAPID ([RFC 8292](https://www.rfc-editor.org/rfc/rfc8292)). The push service never sees task titles or messages.
- Offline alerts include the agent profile, model and runner identity, plus the approval command/path or question preview, for the same presentation as live alerts.
- Zero dependencies; Node 22+. State (VAPID keys, pairing key, device tokens and encryption keys) lives in `~/.forge-relay/state.json` with mode 0600.
- When the phone is connected to Kandev, Forge's own monitor shows alerts and drops duplicate pushes.

## Prerequisites

This is optional. Use your own Node.js 22+ host, Kandev v0.98.0 address, Firebase project
and Firebase service account. Phones need Google Play services for direct FCM. LAN/Tailscale
access to the relay is needed for pairing, token refresh and unpairing; FCM delivery then works
without an inbound connection to the relay. Do not expose the relay to the public internet.

## Firebase and APK setup

1. In your Firebase project, register Android package `ca.mslab.forge` for release and/or
   `ca.mslab.forge.debug` for debug. Download the matching `google-services.json` to `app/`.
   Both packages may be configured in one project file. Package names must match exactly;
   see [Firebase Android setup](https://firebase.google.com/docs/android/setup).
2. Build and install that variant using the [main build instructions](../README.md#build-and-connect).
   Without a matching Firebase configuration, the app still builds and works, but FCM is unavailable.
3. Enable the Firebase Cloud Messaging HTTP v1 API. Create a service account with FCM send
   permission (for example Firebase Cloud Messaging API Admin), following the official
   [FCM authorization guide](https://firebase.google.com/docs/cloud-messaging/auth-server).
4. Save its JSON outside the repository, for example `~/.forge/firebase-service-account.json`,
   with mode 0600. This server credential is never installed in the APK. The app configuration
   and service account must belong to the same Firebase project.

## Install the systemd user service

```sh
mkdir -p "$HOME/.config/systemd/user" "$HOME/.forge"
chmod 700 "$HOME/.forge"
cp relay/forge-relay.service "$HOME/.config/systemd/user/"
```

Edit the copied unit's `ExecStart` to match your checkout path and `command -v node`.
Create private `~/.forge/relay.env` (absolute paths, no shell `$HOME` expansion):

```ini
KANDEV_URL=http://127.0.0.1:38429
KANDEV_TOKEN=YOUR_KANDEV_PAT_IF_AUTHENTICATED
RELAY_HOST=192.168.1.10
RELAY_PORT=38430
GOOGLE_APPLICATION_CREDENTIALS=/absolute/path/to/.forge/firebase-service-account.json
```

Replace the bind address with this host's actual private LAN/Tailscale address; omit the
KANDEV_TOKEN line if authentication is disabled. Default bind without configuration is
`0.0.0.0`; restrict access with your host firewall. State defaults to `~/.forge-relay`;
`RELAY_STATE` overrides the directory. Protect its device tokens, encryption keys and pairing key.

```sh
chmod 600 "$HOME/.forge/relay.env" "$HOME/.forge/firebase-service-account.json"
systemctl --user daemon-reload
systemctl --user enable --now forge-relay
journalctl --user -u forge-relay -n 50
curl --fail http://192.168.1.10:38430/v1/info
curl --fail http://192.168.1.10:38430/v1/health
```

Info should show `fcm: true`; that means a credential file was loaded, not that delivery was
verified. Health should show Kandev connected. Read the pairing key from the private service
log or state file; do not post it in an issue. Enable user lingering if this service must run
while logged out (see the [remote service guide](../remote/README.md#2-install-the-gateway-and-tunnel-services)).

## Pair a phone

1. Install the Firebase-configured Forge release APK on a phone with Google Play services and allow notifications.
2. In Forge, open **Settings → Alerts when away**, enter your relay’s reachable LAN/Tailscale address (port 38430), enter the pairing key, then tap **Pair this phone**. Pairing needs the home network or Tailscale once; after that, alerts arrive anywhere. No ntfy app is required.
3. Tap **Send test**. For an existing ntfy pairing, tap **Change**, then **Pair this phone**; leave the key blank to reuse the saved key. Successful pairing removes the old ntfy subscription.

FCM token changes are saved and registered immediately when the relay is reachable. A failed registration retries when Forge next enters the foreground. The phone must reach the relay to finish that update. Unpairing requires the relay to be reachable.

The relay removes a phone when FCM reports its token as unregistered (for example after a reinstall) and logs the
removal. Each time Forge comes to the foreground with the relay reachable, it checks that it is still registered and
pairs again if not; Settings shows when the relay last confirmed the phone.

## How alerts are delivered

The relay's push is the primary alert. Every alert is sent at FCM HIGH priority and ends in a visible notification,
except for the task on the phone's screen: Forge reports that task to the relay, which skips it (`skip … (on screen)` in
the log). Forge's own live connection only posts an alert when no push arrived within 15 seconds, or at once on a
phone that isn't paired; a push that arrives after such a fallback updates it without a second sound. After a Kandev
outage the relay compares the reloaded tasks with their last known state and alerts on turns that ended meanwhile.

**Settings → Delivery log** on the phone lists each alert with its arrival time, delay, transport, priority (`high→normal`
means FCM lowered it) and what Forge did with it. The relay logs one `push <kind> <device> <status> <ms>` line per send.

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| GET | `/v1/health` | — | Kandev link state, device and task counts |
| GET | `/v1/info` | — | VAPID public key and FCM availability |
| POST | `/v1/devices` | pairing key | Register `{id, transport:"fcm", token, encryption_key, name}` or legacy `{id, endpoint, p256dh, auth, name}` |
| GET | `/v1/devices/:id` | pairing key | Registration check: `{ok, transport, name, added, last}`, or 404 `unknown device` |
| DELETE | `/v1/devices/:id` | pairing key | Unpair |
| POST | `/v1/devices/:id/presence` | pairing key | `{task_id, ttl}`: the task on screen (alerts for it are skipped); `task_id: null` clears it |
| POST | `/v1/devices/:id/test` | pairing key | Send a test alert |

Run `node --test relay/fcm.test.mjs` for FCM authentication, payload encryption, UTF-8 limits and relay registration checks. Android unit tests also decrypt a shared Node-generated fixture and reject tampering.

Run `node relay/webpush.test.mjs` to re-check the encryption.

## Troubleshooting and maintenance

- FCM unavailable in the app: rebuild the installed variant with a matching Firebase package.
- Info reports `fcm: false`: check the service-account path in the unit environment and restart.
- Pairing fails: check the private route, port/firewall and pairing key.
- Pairing works but test delivery fails: check Google Play services, notification permission,
  API enablement, service-account permission and matching Firebase projects. Inspect private relay logs.
- Back up relay state privately to retain paired devices. Unpair a retired phone before discarding it;
  disable the service and revoke the service account when removing the deployment.
