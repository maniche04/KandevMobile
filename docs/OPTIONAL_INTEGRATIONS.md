# Dependencies and optional integrations

An installed Forge APK needs Android 8.0+ and a reachable Kandev v0.98.0 instance.
Use the server's existing workspace, agents and execution environment. None of the integrations
below is required for task navigation, chat, plans or cached history.

## Dependency map

| Capability | Extra dependency | Where it runs | If omitted |
| --- | --- | --- | --- |
| Provider quota strip | Provider Usage plugin and CodexBar (setup below) | Kandev host/container | No usage strip; other screens work |
| Tailscale access | Tailscale connectivity | Phone and server, or a tailnet subnet router | Use LAN or HTTPS instead |
| Cloudflare access | Cloudflare account/domain, cloudflared, Python 3.9+, Node.js 22+ and the included gateway | Operator's server | Direct LAN/Tailscale/HTTPS still works |
| Alerts while unreachable/asleep | Included forge-relay, Node.js 22+, Firebase project/service account; matching Firebase APK configuration and Google Play services | Relay host and phone | Live monitoring and in-app state still work when connected; no FCM fallback |
| Terminal rendering | Enabled Android System WebView | Phone | Terminal panel cannot render; other screens work |
| Git/PR operations and shells | Git, repository credentials and tools supported by the Kandev executor | Kandev execution environment | Affected server operations are unavailable |
| Open an attachment in another app | An Android app handling that file type | Phone | Use supported native previews or Save a copy |

The terminal's xterm JavaScript and fonts are bundled; no separate terminal app or xterm server
is required on the phone. Forge's native theme does not require the Forge Personal web plugin.
The legacy UnifiedPush receiver remains for existing pairings; new away-alert pairing uses FCM.
An ntfy app/server is not a prerequisite for the current setup.

Build dependencies are separate from runtime integrations: source builders need JDK 17,
Android SDK 36 and dependency downloads from the Gradle/Maven repositories. The wrapper downloads
Gradle. Java/Kotlin libraries (including the Firebase and UnifiedPush SDKs) are build dependencies
resolved by Gradle; optional Firebase configuration means FCM can remain inactive, not that its
SDK is removed from the APK. Installing an existing APK requires no JDK, Gradle, Python or Node
on the phone. No Node/Go/Swift toolchain is needed merely to build the Android app.

## Optional provider usage setup

Usage means provider/account subscription quotas, not task token billing. Forge reads only
`GET /api/plugins/kandev-provider-usage/webhooks/providers`; it does not run CodexBar or request
provider credentials on the phone.

### Server prerequisites

For this app's pinned Kandev v0.98.0, use the **Provider Usage v0.12.0** baseline already deployed
with this project. Do not blindly install plugin `main`: its newer SDK declares Kandev 0.99.0+,
which Forge currently rejects. Compare compatibility before upgrading either component.
See the [v0.12.0 manifest](https://github.com/kdlbs/kandev-plugin-provider-usage/blob/v0.12.0/manifest.yaml)
and [current SDK compatibility notes](https://github.com/kdlbs/kandev-plugin-provider-usage#develop).

1. Download the v0.12.0 package from [plugin releases](https://github.com/kdlbs/kandev-plugin-provider-usage/releases/tag/v0.12.0).
   Install it through Kandev's **Settings → Plugins → Install plugin → Upload**.
2. Open **Provider Usage** settings. CodexBar can be resolved from a configured executable,
   PATH, or the plugin's managed download. Managed installation needs outbound GitHub access
   and a writable cache. See the [versioned operator guide](https://github.com/kdlbs/kandev-plugin-provider-usage/blob/v0.12.0/README.md#codexbar-distribution).
3. Complete provider authentication as the same OS user running Kandev. In containers,
   CodexBar and credentials must be available inside that runtime. Use each provider's setup
   instructions; Copilot's CodexBar authorization is separate from its agent CLI login.
4. Enable only the desired providers and refresh usage in the plugin settings.

For a manually managed CLI, follow [CodexBar's install guide](https://github.com/steipete/CodexBar/blob/main/docs/cli.md#install),
then set its absolute executable path in the plugin. Provider credentials stay server-side;
never copy them into the APK or repository. Full provider setup is maintained in the
[plugin's versioned README](https://github.com/kdlbs/kandev-plugin-provider-usage/blob/v0.12.0/README.md).

### Verify from Forge

First confirm the endpoint returns JSON from a trusted machine:

```sh
# Substitute your own reachable base URL. For authenticated Kandev, add its bearer header.
curl --fail http://192.168.1.10:38429/api/plugins/kandev-provider-usage/webhooks/providers
```

Look for a nonempty `providers` array and quota `windows`. Reopen Forge's home screen; tap a
provider chip for details and **Refresh**. The app revalidates at most every ten minutes while
home is shown; explicit refresh requests a fresh provider read at most once per minute. These
phone intervals are separate from the plugin's server poll interval.

The supplied Cloudflare gateway already permits this GET endpoint. It does not expose plugin
installation, configuration or update routes; configure the plugin through your trusted Kandev
admin connection. Another reverse proxy must forward this route with the same authentication
as the rest of Kandev.

### Missing, failing or removed integrations

- **404:** Forge hides the usage strip and removes its cached report. Leave the plugin uninstalled
  if you do not want telemetry; there is no extra mobile setup to skip.
- **401/403:** check the Kandev token or proxy policy. A usage fetch does not demote the main
  connection route. Previously saved quota data may remain visible with an error/staleness hint.
- **No providers/windows:** the strip stays hidden. Check provider sign-in, enabled providers
  and CodexBar diagnostics in the server plugin settings.
- **Network/server/invalid JSON:** cached usage may remain visible with a hint; chat is independent.
- To remove the feature, disable/uninstall the server plugin through Kandev. Forge clears cached
  usage after the endpoint returns 404 on its next scheduled fetch (or explicit refresh).
  A disabled plugin that still serves cached JSON can continue to appear until it stops serving it.

These fallback paths are covered by `UsageControllerTest`; visibility without a report is also
covered by `UsageStripTest`. Installing a plugin is an operator choice, not a connection prerequisite.

## Other setup guides

- [Cloudflare gateway, enrollment and revocation](../remote/README.md)
- [FCM relay and Firebase configuration](../relay/README.md)
- [Direct connection and source builds](../README.md#build-and-connect)
