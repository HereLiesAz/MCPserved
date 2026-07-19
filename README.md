# MCPserved

A desktop MCP server and an Android application that let an authorized client
observe and control a phone — over plain `adb` for a quick connect, or through
the on-device app for a richer, per-package-granted surface.

Everything is local. There is no relay and no cloud in the path: control travels
a USB cable or an adb-over-Wi-Fi session the user set up themselves. The desktop
server holds no authority of its own — it sits downstream of a language model's
output, which makes it the component least suited to being the thing that says
yes.

## Shape

~~~
                          ┌─ adb: input / uiautomator / screencap        (quick connect)
Claude ─ MCP server ─────┤
        (stdio)           └─ adb forward → 127.0.0.1 → on-device app      (paired upgrade)
~~~

Two backends behind one interface. The default drives the device straight over
`adb`, so a model can control any phone with USB debugging (or adb-over-Wi-Fi)
enabled — no app to install. When the on-device app is installed, paired, and
reachable, the server upgrades to it for the semantic accessibility tree,
per-package grants, and the notification mirror. `MCPSERVED_MODE` pins the choice
to `adb` or `app`; the default, `auto`, prefers the app and falls back.

The app never connects out. It binds a control server to `127.0.0.1` on the
phone; `adb forward` bridges a desktop port onto it. Loopback is not routable, so
nothing off-device can reach it, and the pairing key authenticates the one peer
that may.

## Control layers

Backends are not tiers. Root is not uniformly better — `su -c input tap` spawns a
process per gesture and lands ~200 ms behind `dispatchGesture`, while root is the
only path to a screenshot that skips the MediaProjection dialog. The resolver
dispatches per operation.

| Operation | Preferred | Fallback |
| --- | --- | --- |
| `ui_tree`, `scroll` | Accessibility | — |
| Gestures, text, keys | Accessibility | root, Shizuku |
| `screenshot` | root `screencap` | MediaProjection |
| `launch`, `shell` | root | Shizuku |

The pure-adb backend covers the same operations with `input`, `uiautomator dump`,
and `screencap`. It has no notification mirror or clipboard access — those need
the app — and it says so rather than faking them.

## Authorization

The on-device app authorizes per package, per scope, revocable, expiring by
default.

| Scope | Permits |
| --- | --- |
| `OBSERVE` | read the screen and notifications |
| `INTERACT` | tap, swipe, scroll, press keys |
| `TYPE` | enter text, set the clipboard |
| `LAUNCH` | bring the app to the foreground |
| `SHELL` | run shell commands while the app is open |

There is no denylist of sensitive applications. A denylist enumerates badness and
is wrong the moment something is installed or rebranded. An empty grant table
renders the whole service inert, which is the correct resting state.

Every mutating operation is bracketed: read the foreground package, check the
grant, act, read the foreground package again. The second read exists because a
window can change between check and act.

The pure-adb backend has **no** per-package grant model — `adb` holds shell-level
authority over the whole device, which is exactly what enabling USB debugging
conferred. That is disclosed in the capability report and the session notice
rather than dressed up as something narrower. When per-app authority matters, use
the paired app.

## Setup

**Quick connect (adb).** Enable USB debugging on the phone and attach it, or pair
it over Wi-Fi with `adb connect <ip>:5555`. Then point the MCP host at the server:

~~~bash
cd mcp
npm install
npm run build
node dist/index.js          # MCPSERVED_MODE defaults to auto → adb when unpaired
~~~

Set `MCPSERVED_ADB_SERIAL` to select a device when more than one is attached (a
USB serial, or an `ip:port` for Wi-Fi), and `MCPSERVED_ADB` to point at a
non-PATH adb binary.

**Paired app (upgrade).** Install the app, enable the accessibility service, arm
it, and grant packages. Then pair — a mutual QR exchange, both public keys out of
band in both directions so nothing sits in the exchange that establishes trust:

~~~bash
npx mcpserved pair
~~~

With a pairing on file and the device reachable, the server uses the app
automatically; it sets up the `adb forward` tunnel itself on connect.

## Known constraints

- **The screen stays on during an app session.** Accessibility events stop and
  the node tree empties once the device locks, so a session on a dark screen
  fails every action while appearing merely unlucky. Keep the TTL short.
- **Shizuku dies on reboot.** Without root, every restart costs a manual re-pair.
- **`adb input text` is ASCII-ish.** Spaces are handled; newlines and most
  non-ASCII are not. The app's text backend handles the rest.
- **adb has no clipboard or notification list.** Those operations need the app.
- **Play still requires disclosure.** Removing the relay removes the remote-
  control profile that gets accessibility apps pulled, but the AccessibilityService
  use must still be declared honestly (Permissions Declaration + a prominent
  in-app disclosure). The desktop adb server is not a Play app and has no such
  exposure.
- **iOS is impossible.** No third-party iOS application can automate another.

## Not yet done

- `MediaProjection` capture is declared as a capability but has no
  implementation; unrooted devices currently cannot screenshot through the app
  (the adb backend's `screencap` works regardless).
- Launcher icon is the template's.
- No tests.

## Layout

~~~
app/     Android application — service, backends, grants, crypto, loopback server, UI
mcp/     Desktop MCP server — stdio transport, tool schemas, adb + app backends
~~~
