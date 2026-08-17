# Google Play — permissions declaration reference

Working notes for the Play Console submission. This is the honest account of what
the app does and why it holds each sensitive permission; paste and adapt the
relevant parts into the Console forms. Nothing here should be softened to pass
review — the design was changed so that the accurate description *is* the
compliant one.

## What the app is

MCPserved lets a desktop client the user pairs with the device operate the phone
on the user's behalf: reading the screen and dispatching taps, swipes, and text
entry, restricted to applications the user has explicitly granted. By default,
control travels a **local** connection the user establishes with `adb` (USB, or
adb-over-Wi-Fi). By default, the app makes **no** network connections and sends
screen contents to no server.

Four remote-access paths exist, all **opt-in, off by default, and gated
behind their own prominent disclosure** the user must affirmatively accept
before any can be turned on (see "Remote access — permissions declaration"
below): binding the on-device MCP endpoint to every network interface instead
of loopback, for use behind a private mesh (Tailscale, WireGuard) the user
installs and joins separately; listening on the device's own global IPv6
address directly, with no router or third party involved; requesting a UPnP
port mapping from the user's own router so the device is directly reachable
with no relay and no third party; and dialing out to an operator-configured
relay. The IPv6, UPnP, and relay paths all carry only the already end-to-end
encrypted sealed-frame protocol and never see plaintext. None is reachable,
and none is described in this document's other sections as anything but
explicitly opt-in.

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

## Remote access — permissions declaration

**Prominent disclosure + consent:** a separate dialog from the accessibility
one, shown the first time the user turns on any remote-access path (see
`ConsentStore.acceptRemoteAccess`, `RemoteAccessStore`), requiring an
affirmative tap before the toggle takes effect. Turning a path back off never
shows it again. Text shown:

> This lets MCPserved be reached beyond the USB cable or Wi-Fi network you're
> on right now — by listening on this phone's own IPv6 address, by asking
> your router to open a port automatically, by a relay you point it at, or
> by a private mesh you join separately. None of these is on until you
> confirm here, and all stay off unless you turn them on explicitly below.
>
> [ I understand and agree ]   [ Not now ]

**What each path actually does, for the reviewer:**

- **Mesh bind** (`McpServer` binding `0.0.0.0` instead of `127.0.0.1`): still
  authenticated by the same bearer token as the loopback default; the device
  itself does not create, join, or depend on any specific mesh technology —
  it only widens which interface the socket accepts connections on. No new
  network traffic is generated by this app; it changes what already-running
  traffic can reach.
- **IPv6 listener** (`LocalServer.setIpv6Enabled`): the device binds its own
  current global IPv6 address (queried locally via `NetworkInterface`, no
  network request involved) on the same already end-to-end encrypted
  sealed-frame port. No relay, no router request, no third party of any
  kind — this is the device listening on an address it already has. Whether
  that address is reachable from outside the local network is a property of
  the network's own firewall, entirely outside the app's control; rechecked
  every few minutes since the address itself can rotate or change.
- **UPnP port mapping** (`UpnpPortMapper`): the device sends a UDP SSDP
  discovery request to the local network's router and, if one responds as a
  UPnP IGD, requests it forward an external port to the device's already
  end-to-end encrypted sealed-frame port. No third party is involved — the
  request and response stay between the device and the user's own router.
  Re-requested every few minutes rather than left standing indefinitely,
  since the router can revoke or move the mapping on its own schedule.
- **Relay dial-out** (`RemoteRelayClient`): the device makes an outbound
  WebSocket connection to a URL the user supplies, carrying only the sealed
  frames of the existing paired-app protocol (X25519 + ChaCha20-Poly1305,
  documented in `docs/guide/security.md` and `docs/guide/protocol.md`). The
  relay operator — whether the user's own server or a third party fronting
  it — receives only ciphertext it cannot decrypt; MCPserved's own crypto
  keys never leave the device.

## Other declared permissions

| Permission | Why | Notes for review |
| --- | --- | --- |
| `INTERNET` | Required by the platform to open any socket, including the loopback `ServerSocket` the control channel binds to `127.0.0.1`. By default, no outbound or remote connections are made and the only listener is on loopback, reached via `adb forward`. Four opt-in, disclosure-gated paths widen this — see "Remote access" above. | The INTERNET permission is requested regardless of whether remote access is ever turned on, since it is also needed for the default loopback socket. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_SPECIAL_USE` | The control service must stay resident while armed so the paired client can start a session, and it shows the ongoing session notification. | No narrower FGS type fits "user-authorized device automation"; the special-use subtype string states the purpose. Claiming a type that does not fit would be a false declaration. |
| `WAKE_LOCK` | Holds the screen on during a session — accessibility events stop and the node tree empties once the device locks, so actions would silently fail otherwise. | Bounded to the session TTL; released when the session ends. |
| `POST_NOTIFICATIONS` | The ongoing session notification is the user's continuous indicator and fastest stop control. | — |
| `RECEIVE_BOOT_COMPLETED` | Re-arms the service after reboot **only if** it was armed beforehand. | Cleared on explicit disarm; never re-arms an app the user switched off. |
| `CAMERA` | Scanning the pairing QR the desktop server prints. | Used only on the pairing screen; `android:required="false"`. |
| `QUERY_ALL_PACKAGES` | The grants screen lists installed apps so the user can choose which to authorize; a filtered list would silently hide apps they meant to grant. | Used to populate the grant UI, not to profile the device. |

## Data safety

- **Data collected / shared:** none. The app has no analytics, no accounts, no
  backend.
- **Screen contents** read via accessibility are relayed only to the paired
  client — by default over the loopback/adb connection, or, only if the user
  has explicitly opted into and accepted the disclosure for remote access, over
  a private mesh, a direct IPv6 connection, a UPnP-mapped direct connection, or
  as sealed ciphertext through a relay (see "Remote access" above). Never
  persisted, and never transmitted off-device in a form the app itself, a mesh
  peer, whoever dials the IPv6 or UPnP-mapped address, or a relay operator can
  read.
- **Pairing keys** are stored in `EncryptedSharedPreferences` on the device and
  never leave it, regardless of which transport is in use.
- **Network:** by default, the app opens no internet connections, and the
  loopback listener is reachable only through an `adb forward` tunnel the user
  sets up. If the user opts into remote access: the mesh-bind path still opens
  no connection of its own (it only widens which interface an existing socket
  accepts on); the IPv6 path likewise opens no connection of its own — it
  binds an address the device already has; the UPnP path sends discovery/
  mapping requests only to the local network's own router, never to a third
  party; the relay path opens one outbound WebSocket connection to a URL the
  user supplies, carrying only already-encrypted protocol frames.

## Distribution note

The desktop MCP server (the `mcp/` npm package) is not part of the Play
submission — it is a developer tool the user runs on their own computer. Only the
Android app is submitted.
