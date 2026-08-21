# Android app

The on-device half. It reads and touches the screen (via accessibility or, on
the `playstore` build, Shizuku/root — see below), holds the grant table,
hosts the loopback control server, and shows the UI. The app is inert until
several deliberate steps are taken; each is described below.

Ships as two build flavors, same UI and feature set except where noted:
**`github`** (accessibility present, sideloaded from GitHub Releases) and
**`playstore`** (no accessibility service at all — Shizuku instead — built
for Play Store distribution, where Accessibility's restricted-use policy is
a poor fit for what this app does). See [backends](backends.md#two-build-flavors)
for the technical split.

The single Activity has five destinations: **Status**, **Connect**, **Grants**,
**Macros**, and **Log**. Everything with a lifetime beyond the screen lives
in the service.

- **Connect** ("Connect a model") is itself three tabs: **This phone**
  (remote access — wider bind, IPv6, UPnP, relay, the one-tap Quick Tunnel;
  see [remote-access](remote-access.md)), **Direct** (the on-device
  MCP-over-HTTP endpoint and bearer token; see [connect](connect.md)), and
  **Desktop bridge** (pairing with the desktop app or `mcpserved` CLI —
  there is no separate "Pair" destination anymore, this tab is it).
- **Macros** records a named, package-scoped sequence of taps, holds, and
  typed text while the target app is open, then lets it be replayed later —
  locally, or by an AI host calling `macro_run`. Root's raw-touch-event
  recorder is used whenever root is present (it also captures swipes);
  otherwise recording falls back to watching the accessibility event stream,
  which can't capture swipes, scrolls, or global keys at all (no raw gesture
  data in that stream). On the `playstore` flavor — no accessibility service
  to fall back to — recording is available only when root is; see
  [protocol.md](protocol.md#macros) for the wire-level detail.

## The disclosure / consent gate

The very first screen is a **prominent disclosure** (`DisclosureScreen`). It gates
the entire UI: until the disclosure is accepted with an affirmative tap, there is
no way through to the accessibility-settings shortcut, the pairing screen, or the
arm control. The accessibility service is the whole product and the permission
most abused by malware, so the user is told plainly, before any settings screen,
what granting it will and will not allow.

- Acceptance is stored as one durable bit (`ConsentStore`, plain
  SharedPreferences — this records a decision, not a secret).
- It is recorded once and never cleared in-app. **Revoking consent is
  uninstalling the app**, which is the honest scope for a decision this broad.
- Declining calls `finish()` — the app closes.

The disclosure states plainly that the button is *not* the last line of defence:
the service still cannot bind until enabled in system settings, cannot connect
until paired, and cannot act until a package is granted.

### The remote-access disclosure

A second, later, narrower gate covers the opt-in remote-access paths (see
[remote-access](remote-access.md)): the first time the operator turns on the
wider-bind, IPv6, UPnP, or relay toggle — or taps the one-tap Quick Tunnel
button — on the "This phone" tab, a dialog explains what turning it on
actually does before it takes effect. Declining leaves it off; accepting is
recorded as a second durable bit (`ConsentStore.acceptRemoteAccess`),
separate from the accessibility disclosure above, since a device that never
enables remote access never needs to see it.

## Enabling the accessibility service (`github` flavor)

This section describes the `github` flavor's onboarding path. On `playstore`,
where `McpAccessibilityService` isn't declared at all, the **Status** screen's
button instead walks through installing Shizuku, pairing it over wireless
debugging, and granting it permission — see
[backends.md](backends.md#two-build-flavors) for what that backend can and
can't do differently. Both flavors otherwise arm and disarm the same way.

`McpAccessibilityService` is the only handle to the window tree and the only cheap
source of the current foreground package. It must be enabled in system settings —
the **Status** screen offers an **Open settings** button that jumps there.

- The service subscribes to `TYPE_WINDOW_STATE_CHANGED` **only** — enough to know
  the foreground package for grant enforcement — never to text/content-changed
  events, which would deliver keystrokes from ungranted apps.
- The service publishes itself to a static `instance` on connect and clears it on
  destroy. Android tears the service down on toggle, on update, and occasionally
  for no stated reason; nothing retains a stale reference, because a stale binder
  yields silent no-ops rather than errors.
- `isAccessibilityTool` is **not** set: the app uses the API for control, not as
  an assistive aid, and claiming otherwise would be a false declaration.

## Notification access

`NotificationMirror` is a separate `NotificationListenerService`, bound through
its own permission and lifecycle — enabled independently of accessibility, and
either may be absent while the other runs. The Status screen marks it **optional**
and offers a settings shortcut. Without it, the `notifications` tool returns
"notification access not granted".

Filtering to granted packages happens at **read time**, not at post time. If it
filtered on arrival, the mirror's contents would depend on what was granted when
each notification landed, so revoking a grant would leave already-captured content
readable.

## Arming

**Arming** starts `ControlService`, a foreground service that holds everything
with a lifetime longer than one request: the backend resolver, the grant store,
the session, the wakelock, and the loopback control server. The Status screen's
**Arm** / **Disarm** button toggles it.

The service runs **whenever the app is armed, not only during a session** — it
must be resident so the desktop server can reach the loopback port and open a
session at all. A service that only existed during sessions could never be told to
begin one.

The **ongoing notification** is not a formality. It is the operator's only
continuous indication that something holds authority over the device, and it
carries the fastest revocation controls:

| Action | Effect |
| --- | --- |
| **Stop** (shown only during a live session) | Ends the session, releases the screen hold. Leaves the service armed. |
| **Revoke all** | Ends the session and clears **every** grant. The panic control. |
| **Disarm** | Ends the session and stops the service entirely, closing the loopback server. |

## Per-package grants and scopes

`GrantsScreen` is the security model in its entirety. There is **no denylist** of
sensitive applications — a denylist enumerates badness and is wrong the moment
something is installed or rebranded. Nothing is reachable until it appears in the
grant table with a scope; an empty table renders the whole service inert, which is
the correct resting state. Granted packages sort to the top.

Scopes are individually selectable (not only presets):

| Scope | Permits |
| --- | --- |
| `OBSERVE` | Read the screen and notifications. |
| `INTERACT` | Tap, swipe, scroll, press keys. |
| `TYPE` | Enter text, set the clipboard. |
| `LAUNCH` | Bring the app to the foreground. |
| `SHELL` | Run shell commands while the app is open. |

Grant properties (`Grant`):

- **Additive, default absent.** A missing scope means refusal.
- **Expiry.** The scope dialog offers **1 hour** (default) or **Until revoked**.
  Bounded grants are the default; unbounded ones require deliberate selection.
- **Replacement is total, not a merge** — re-granting with a narrower scope set
  narrows. Setting an empty scope set **revokes** rather than storing a grant that
  permits nothing.
- Persisted in DataStore, so a service restart neither widens nor narrows what the
  caller may reach. Expired grants are pruned lazily on read, not by a timer that
  might not fire during doze.

Preset scope sets exist for convenience: `READ_ONLY` = {OBSERVE};
`INTERACT` = {OBSERVE, INTERACT, TYPE, LAUNCH}; `FULL` adds SHELL.

## Sessions

A **session** is the window during which the remote caller may act. It is **not a
connection**: the socket may drop and re-establish freely without ending the
session, and a session may expire while the socket is healthy.

- Opened by the `session_begin` tool with a TTL in seconds, **clamped to
  30–1800** (default 300). An unbounded session is not offered.
- **The screen is held awake for the session's duration.** Accessibility events
  cease and `rootInActiveWindow` returns null once the device locks — the tree
  goes empty and every action fails while appearing merely unlucky. Holding the
  screen is the honest version of that constraint: visible, battery-hostile, and
  impossible to forget is running. The hold prefers `svc power stayon true` when a
  privileged backend exists, else a `SCREEN_BRIGHT_WAKE_LOCK` bounded to the max
  TTL. **Keep the TTL short.**
- **Extended on success only.** Every successful action pushes expiry back by the
  default TTL; a failed action does not. A caller stuck retrying against a screen
  that will never accept input hits the TTL rather than outliving it.
- Expiry is checked on read and by a 5-second **reaper** poll (not a timer, which
  fires late under doze). On lapse the screen hold is released and the log cleared.
- Every action is **logged** while a session is open (`SessionLog`, `LogRow`).
  The log is **in-memory and clears when the session ends** — a durable log of
  everything an agent did to the phone would itself be a record of everything on
  the phone. Denied entries render in red. Capacity 500 entries, newest first.

Only `capabilities` and `session_begin` are answerable without a live session;
everything else fails closed with "no active session".

## Revocation

Two distinct kinds, doing different things:

| Kind | What it does | Does not |
| --- | --- | --- |
| **Grants** (Revoke all / empty the table / per-package) | Stops the peer from *doing* anything — every operation is denied. | Stop the peer from *connecting*: a peer holding a valid shared secret can still reach the loopback port and be told no. |
| **Identity rotation** (Pair screen → **Rotate identity**) | Mints a new X25519 key and forgets the old peer. The **only complete revocation** — the peer cannot even arrive. | — |

Rotation runs on first launch and on explicit re-pair. See [security](security.md).

## Boot behavior

`BootReceiver` restarts the service after a reboot **only if it was armed
beforehand**. Arming is a deliberate act and should survive a restart; coming back
armed when the user had disarmed would be the exact failure this design guards
against. The armed flag is cleared on explicit disarm and nowhere else.

On an unrooted device the **Shizuku binding does not survive the reboot**, so the
service returns with a narrower capability set until the user re-pairs Shizuku.
That is reported honestly through the capability query rather than discovered
through a tool call that fails. See [backends](backends.md) and
[troubleshooting](troubleshooting.md).

## Declared permissions (reference)

| Permission | Why |
| --- | --- |
| `BIND_ACCESSIBILITY_SERVICE` | Read the screen and dispatch input. **`github` flavor only** — removed entirely from `playstore`'s manifest (`app/src/playstore/AndroidManifest.xml`); that flavor doesn't declare this permission at all and uses Shizuku instead. |
| `INTERNET` | Platform requirement to open any socket, including the loopback `ServerSocket`. Makes no outbound connections by default; opt-in remote access can (see [remote-access](remote-access.md)), including a bundled `cloudflared` binary the one-tap Quick Tunnel launches as a subprocess. |
| `WAKE_LOCK` | Hold the screen during a session. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | Keep the control service resident while armed. No narrower FGS type fits "user-authorized device automation". |
| `FOREGROUND_SERVICE_MEDIA_PROJECTION` | API 34+ requires the foreground service invoking MediaProjection APIs to declare this type. Declaring it doesn't start a capture by itself — that still needs the operator's one-time consent grant (see [backends](backends.md#screen-capture-without-root-mediaprojectionbackend)). |
| `POST_NOTIFICATIONS` | The ongoing session notification. |
| `RECEIVE_BOOT_COMPLETED` | Re-arm after reboot only if armed beforehand. |
| `CAMERA` (`required=false`) | Scan the pairing QR, on the Connect → Desktop bridge tab. |
| `QUERY_ALL_PACKAGES` | Populate the grants screen; a filtered list would silently hide apps the user meant to grant. |

Neither flavor requests a dangerous permission for Shizuku or root — Shizuku's
own permission model and grant are entirely between the operator and the
Shizuku app, outside this manifest.

The app collects and shares no data on its own, has no analytics, no
accounts, and no backend. Pairing keys live in `EncryptedSharedPreferences`
and never leave the device. Opt-in features are the exception: the in-app
Cloudflare relay deploy sends a user-pasted API token to Cloudflare's own
API, and the Quick Tunnel routes the control connection through Cloudflare's
network while it's running — both off by default, both behind the
remote-access disclosure above.
