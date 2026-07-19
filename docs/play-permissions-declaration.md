# Google Play — permissions declaration reference

Working notes for the Play Console submission. This is the honest account of what
the app does and why it holds each sensitive permission; paste and adapt the
relevant parts into the Console forms. Nothing here should be softened to pass
review — the design was changed so that the accurate description *is* the
compliant one.

## What the app is

MCPserved lets a desktop client the user pairs with the device operate the phone
on the user's behalf: reading the screen and dispatching taps, swipes, and text
entry, restricted to applications the user has explicitly granted. Control
travels a **local** connection the user establishes with `adb` (USB, or
adb-over-Wi-Fi). The app makes **no** network connections and sends screen
contents to no server.

It is not primarily a disability aid, so `android:isAccessibilityTool` is **not**
set on the service. It is an automation/agent tool that uses the Accessibility
API for control, disclosed as such. Do not set `isAccessibilityTool="true"` — the
app does not qualify, and claiming it would be a false declaration.

## AccessibilityService — permissions declaration

**Which permission:** `BIND_ACCESSIBILITY_SERVICE` (the `AccessibilityService`
API), used with `canRetrieveWindowContent` and `canPerformGestures`.

**How the app uses it (for the declaration field):**

> MCPserved uses the AccessibilityService API to let a client the user has paired
> — running on the user's own computer — carry out on-screen actions the user
> requests: reading the current screen's text and structure, and dispatching
> taps, swipes, scrolls, and text input. This is the app's core function:
> hands-free/remote operation of the device by an assistant the user runs
> themselves. The service acts only for applications the user has explicitly
> granted, only while a user-started, time-limited session is open, and only over
> a local connection the user establishes with adb. It performs no data
> collection and makes no network connections.

**Event scope:** the service subscribes to `typeWindowStateChanged` only — enough
to know the foreground package for grant enforcement — rather than to
text/content-changed events, which would deliver keystrokes from ungranted apps.

**Prominent disclosure + consent:** shown in-app before the user is sent to the
accessibility settings, and requires an affirmative tap. See the text below; it
mirrors `DisclosureScreen`.

**Demo video:** Play typically requires a short video for accessibility use.
Record: the disclosure screen and acceptance → enabling the service in settings →
pairing → arming → granting one package → the paired client performing one action
on that app, with the ongoing session notification visible.

## Prominent disclosure text (in-app, before enabling)

> MCPserved lets a desktop client you pair with this device read the screen and
> perform taps, swipes, and text entry on your behalf, so that an assistant
> running on your own computer can operate the phone for you.
>
> **It uses the Accessibility Service** to read the screen and dispatch input.
> That is a powerful permission; this app uses it only to carry out the actions
> the paired client requests, and only for the applications you explicitly grant.
>
> **It stays on your device.** The client connects over a local connection you
> set up yourself with adb (USB, or adb-over-Wi-Fi). The app makes no connection
> to the internet and sends your screen contents to no server.
>
> **You stay in control.** Nothing can happen until you enable the service, pair
> a client, arm the app, and grant specific packages. Each session is
> time-limited and shown in an ongoing notification you can stop at any moment,
> and every action is logged while a session is open.
>
> [ I understand and agree ]   [ Not now ]

## Other declared permissions

| Permission | Why | Notes for review |
| --- | --- | --- |
| `INTERNET` | Required by the platform to open any socket, including the loopback `ServerSocket` the control channel binds to `127.0.0.1`. | No outbound or remote connections are made; the only listener is on loopback, reached via `adb forward`. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | The control service must stay resident while armed so the paired client can start a session, and it shows the ongoing session notification. | No narrower FGS type fits "user-authorized device automation"; the special-use subtype string states the purpose. Claiming a type that does not fit would be a false declaration. |
| `WAKE_LOCK` | Holds the screen on during a session — accessibility events stop and the node tree empties once the device locks, so actions would silently fail otherwise. | Bounded to the session TTL; released when the session ends. |
| `POST_NOTIFICATIONS` | The ongoing session notification is the user's continuous indicator and fastest stop control. | — |
| `RECEIVE_BOOT_COMPLETED` | Re-arms the service after reboot **only if** it was armed beforehand. | Cleared on explicit disarm; never re-arms an app the user switched off. |
| `CAMERA` | Scanning the pairing QR the desktop server prints. | Used only on the pairing screen; `android:required="false"`. |
| `QUERY_ALL_PACKAGES` | The grants screen lists installed apps so the user can choose which to authorize; a filtered list would silently hide apps they meant to grant. | Used to populate the grant UI, not to profile the device. |

## Data safety

- **Data collected / shared:** none. The app has no analytics, no accounts, no
  backend.
- **Screen contents** read via accessibility are relayed only to the paired local
  client over the loopback/adb connection and are never persisted or transmitted
  off-device by the app.
- **Pairing keys** are stored in `EncryptedSharedPreferences` on the device and
  never leave it.
- **Network:** the app opens no internet connections. The loopback listener is
  reachable only through an `adb forward` tunnel the user sets up.

## Distribution note

The desktop MCP server (the `mcp/` npm package) is not part of the Play
submission — it is a developer tool the user runs on their own computer. Only the
Android app is submitted.
