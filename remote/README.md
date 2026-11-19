# Optional Cloudflare remote access

Skip this guide if direct LAN, Tailscale or an existing authenticated HTTPS endpoint works for you.
This deployment uses **your own hostname and Cloudflare account**. The APK has no dependency
on the maintainer's domain, tunnel or credentials.

Traffic flow: phone → HTTPS / Cloudflare Access → cloudflared → Forge gateway → Kandev.
Access uses one 90-day service token per phone, plus a separate per-phone origin token.
Cloudflare terminates TLS and can see app traffic. Enrollment files are bearer credentials;
transfer them privately and delete transfer copies after import.

## Prerequisites and inputs

- Kandev v0.98.0 or newer reachable from the gateway over trusted HTTP (default `http://127.0.0.1:38429`).
- Linux with a systemd user manager, Python 3.9+, Node.js 22+, and `cloudflared` installed.
- A domain in your Cloudflare account, an unused hostname such as `forge.example.com`, and
  a configured Zero Trust organization / team domain.
- Your Cloudflare account ID, zone ID, and an API token scoped to that account and zone:
  Account: Cloudflare Tunnel Edit, Access Apps and Policies Edit, Access Service Tokens Edit,
  Access Organizations Read. Zone: DNS Edit, Zone Read.

Consult Cloudflare's [service token guide](https://developers.cloudflare.com/cloudflare-one/access-controls/service-credentials/service-tokens/)
and [tunnel installation guide](https://developers.cloudflare.com/cloudflare-one/networks/connectors/cloudflare-tunnel/downloads/).
The generated per-host ingress enables [Access JWT verification](https://developers.cloudflare.com/tunnel/reference/origin-parameters/#access-settings)
with your application audience; retain it and the final catch-all 404 rule.

## 1. Prepare private configuration

From the repository root, create operator files **outside the checkout**:

```sh
mkdir -p "$HOME/.forge/remote"
chmod 700 "$HOME/.forge" "$HOME/.forge/remote"
```

Save the API token in `~/.forge/cloudflare-api-token` using an editor (avoid shell history).
Create `~/.forge/cloudflare-context.json`, replacing all three example values:

```json
{
  "account_id": "YOUR_ACCOUNT_ID",
  "zone_id": "YOUR_ZONE_ID",
  "hostname": "forge.example.com"
}
```

```sh
chmod 600 "$HOME/.forge/cloudflare-api-token" "$HOME/.forge/cloudflare-context.json"
python3 remote/access.py setup
```

Setup creates the Access app, tunnel, private tunnel credentials, cloudflared ingress config
and an empty device allowlist. It also records generated IDs in the context file. Keep these
files for subsequent commands. Setup does not publish DNS. Use a dedicated unused hostname;
inspect any existing Access app before reuse (the script only rejects a conflicting app name).

## 2. Install the gateway and tunnel services

The supplied units assume a checkout at `~/projects/KandevMobile`, Node at `/usr/bin/node`
and cloudflared at `~/.local/bin/cloudflared`. **Edit ExecStart in the copied units** to match
`pwd`, `command -v node` and `command -v cloudflared` on your server.

```sh
mkdir -p "$HOME/.config/systemd/user"
cp remote/forge-remote.service remote/forge-tunnel.service "$HOME/.config/systemd/user/"
# Edit both copied units now, before starting them.
systemctl --user daemon-reload
systemctl --user enable --now forge-remote.service forge-tunnel.service
systemctl --user status forge-remote.service forge-tunnel.service
journalctl --user -u forge-remote.service -u forge-tunnel.service -n 50
```

For an authenticated origin or a non-default Kandev address, create `~/.forge/remote.env`:

```ini
FORGE_ORIGIN_URL=http://127.0.0.1:38429
FORGE_ORIGIN_TOKEN_FILE=/absolute/path/to/.forge/kandev-token
```

Omit the token-file line if Kandev authentication is disabled. Otherwise save only its PAT
in that file and set both files to mode 0600. Restart `forge-remote.service` after changes.
The origin URL must be HTTP without a path or credentials. Keep this hop on loopback or a
trusted private network. The gateway injects the origin PAT; phones do not need that PAT.
For containerized Kandev, expose its port to loopback or use a trusted container-network address.
The gateway itself listens only on `127.0.0.1:38431`.

If services must run after logout/reboot, enable user lingering through your host's admin process
(for example `sudo loginctl enable-linger "$USER"`). This is a user-service deployment;
`kandev.service` in the units is only an ordering hint, not a required installation method.

## 3. Publish and enroll

Before publishing, confirm the origin responds locally and the gateway rejects unapproved callers:

```sh
curl -i http://127.0.0.1:38431/api/v1/system/info
# Expected: 401 Device authentication required.
python3 remote/access.py publish
```

A request to `https://forge.example.com/api/v1/system/info` without Access credentials must be
blocked by Access. It must not return Kandev's JSON. Inspect the Access application/policy and
tunnel configuration if that check fails; do not add a Bypass policy.

```sh
python3 remote/access.py approve my-phone "$HOME/.forge/remote/my-phone.json"
```

Transfer this JSON privately to its intended phone. Open **Set up secure remote access → Import
Forge connection** on the connection screen or in Settings. Verify the supported version and
workspace list, open a task and confirm chat reconnects. Do not test setup by sending a prompt,
answering a real approval or running a terminal command. Delete the transferred copy after import.

Only `/api/v1/`, event `/ws`, terminal paths and the optional provider-usage GET route are forwarded.
The gateway strips Cloudflare/cookie headers and never exposes its management token. Attachments
and WebSockets use the same origin-scoped app credentials; credentials are not forwarded to other origins.

## Renewal, lost phones and backups

Credentials expire after 90 days; there is no automatic renewal. For renewal, revoke the old
label then approve it again and import the replacement file. For a lost phone:

```sh
python3 remote/access.py revoke my-phone
python3 remote/access.py approve replacement-phone "$HOME/.forge/remote/replacement-phone.json"
```

Revocation first removes the local origin token and restarts the gateway to close existing
sockets, then deletes the Access policy/service token. A restart closes all phones' current
connections; authorized phones reconnect. If external deletion fails, finish removing the
old policy and service token in Cloudflare; the local gateway denylist change already blocks it.
Disconnecting in Forge clears local data but does not revoke a server credential.

Back up `~/.forge` privately, including context, tunnel credentials and device state. Never put
it in a public repository or screenshot. Removing this deployment requires disabling both user
services and deleting its dedicated DNS, Access app/policies/service tokens and tunnel in Cloudflare.

## Troubleshooting and checks

- Access login HTML / 403: confirm the imported phone token, service-auth policy and hostname.
- Gateway 401: check the phone's origin token/expiry and that `FORGE_REMOTE_STATE` points to its allowlist.
- 502: check `FORGE_ORIGIN_URL`, origin availability and PAT-file permissions.
- Disconnects: inspect both service logs and ensure WebSockets are supported. Android sleep can
  suspend the app; use the optional [FCM relay](../relay/README.md) for away alerts.

```sh
node --test remote/*.test.mjs
```

## Automatic routes

Settings → Connection routes holds optional local and Tailscale addresses and their separate
API tokens. Automatic is the default. When importing a remote connection while connected
to the same server directly, Forge retains that direct address and its cache automatically.
Enrollment JSON can also include `local` / `tailscale` objects (`url`, optional `token`) and
`workspaceAnchor` (a workspace UUID from the paired server).

Saved routes are checked concurrently on startup, network changes, or route failure. Each
alternate must return the expected Kandev version and contain the paired workspace UUID.
This guards against accidentally using another Kandev server; it is not cryptographic server
authentication for plain HTTP. Use plain HTTP only on trusted LANs or inside Tailscale;
HTTPS retains Android's normal CA and hostname verification. Public credentials are never
copied to a direct address. No automatic network discovery or subnet scanning occurs.

The first verified route wins; a stalled local address does not hold up public access. While
that route is healthy and the network is unchanged it stays selected. Network changes cancel
obsolete probes. A failed route is briefly deprioritized while alternatives are checked.
Route selection never repeats an application request; uncertain writes remain uncertain
and require an explicit user decision. All routes share the same database, drafts and cache.
The event socket resubscribes after a route change, and an active terminal reattaches to the
same server PTY without replaying keystrokes. A Tailscale subnet router can reach the saved
LAN address without needing a separate Tailscale address.

Manual Local / Tailscale / Cloudflare modes constrain selection to the selected route type.
The connection indicator shows the active route. Offline mode continues showing cached data;
FCM remains enabled throughout. If the pinned workspace is removed, reconnect/re-pair the
profile to establish a new server anchor before using automatic alternate routes.
