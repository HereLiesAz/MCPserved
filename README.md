# MCPserved

A **Compose desktop application** and an Android application that let an
authorized client observe and control a phone — over plain `adb` for a quick
connect, or through the on-device app for a richer, per-package-granted surface.

The desktop side is a Compose for Desktop app that ships as a native installer
for Windows, macOS, and Linux. It doubles as the MCP server: launched with no
arguments it opens a window for pairing, Wi-Fi discovery, and one-click host
setup; launched with `stdio` it is the headless server an AI host spawns and
speaks to over stdin/stdout.

Everything is local. There is no relay and no cloud in the path: control travels
a USB cable, an adb-over-Wi-Fi session, or a direct LAN socket to a phone the
desktop found over mDNS. The desktop server holds no authority of its own — it
sits downstream of a language model's output, which makes it the component least
suited to being the thing that says yes.

## Shape

~~~
                          ┌─ adb: input / uiautomator / screencap          (quick connect)
Claude ─ MCP server ─────┼─ mDNS discovery → direct LAN socket → app       (auto-discovery)
        (stdio)           └─ adb forward → 127.0.0.1 → on-device app        (paired upgrade)
~~~

Two backends behind one interface. The default drives the device straight over
`adb`, so a model can control any phone with USB debugging (or adb-over-Wi-Fi)
enabled — no app to install. When the on-device app is installed, paired, and
reachable, the server upgrades to it for the semantic accessibility tree,
per-package grants, and the notification mirror. `MCPSERVED_MODE` pins the choice
to `adb` or `app`; the default, `auto`, prefers the app and falls back.

The app never connects out. It binds a control server on the phone; `adb forward`
bridges a desktop port onto its loopback, and — once armed — it also advertises
itself on the LAN over mDNS (`_mcpserved._tcp`) so the desktop can find it and
dial it directly, no cable and no manual `ip:port`. Either way the sealed-frame
pairing key is the boundary: discovery only reveals an address, and an address is
useless to anything that does not hold the paired secret. `auto` mode prefers a
discovered LAN device, then an `adb forward` tunnel, then falls back to raw adb.

## Finding each other

The desktop app and the phone find each other over Wi-Fi with no configuration.
The phone registers a `_mcpserved._tcp` DNS-SD service the moment its control
server is armed, carrying its device id and port; the desktop browses for it
(JmDNS) and lists every device on the network. Pick one and it connects straight
to the advertised address. mDNS over Wi-Fi is the discovery path because it is
the one mechanism both a JVM desktop and Android implement reliably and
symmetrically; Bluetooth has no dependable cross-platform desktop story.

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

**Install the desktop app.** Grab the installer for your OS from the releases
page — `.msi` (Windows), `.dmg` (macOS), `.deb`/`.rpm` (Linux) — or build it:

~~~bash
./gradlew :desktop:packageDistributionForCurrentOS
# installers land in desktop/build/compose/binaries/main/
~~~

Launch it with no arguments and it opens a window with four screens:

- **Devices** — every phone on the Wi-Fi network, discovered automatically, plus
  a Test-connection button that reports the device's capabilities.
- **Pair** — paste the string under the phone's QR code, press Pair, and scan the
  QR that appears back on the phone. One-time, both keys out of band.
- **AI Hosts** — one click wires this server into Claude Desktop, Claude Code,
  Cursor, VS Code, VS Code Insiders, Windsurf, or Cline. "Connect all popular
  AIs" does the lot. No config-file editing.
- **Log** — a running trace of what happened.

It wears the same dark plum-and-amber skin and the same launcher icon as the
phone app, so the two read as one product.

**Always-on (optional).** The Devices screen can install a small background
service — a systemd user unit on Linux, a LaunchAgent on macOS, the current-user
Run key on Windows — that keeps looking for the phone even when the window is
closed. It browses mDNS continuously and caches the device's address, so the
moment an AI host launches the stdio server the connection is already warm
instead of paying a discovery sweep. `mcpserved service install` does the same
from a terminal; `mcpserved service` runs the daemon in the foreground.

**Connect an AI (one click).** The AI Hosts screen registers this app in the
chosen host's MCP config, pointed at the installed executable in `stdio` mode.
The same thing from a terminal:

~~~bash
mcpserved install --all      # or: mcpserved install cursor claude-code
~~~

**Quick connect (adb).** No app needed: enable USB debugging on the phone and
attach it, or pair over Wi-Fi with `adb connect <ip>:5555`. With no pairing on
file, `auto` mode drives the device straight over `adb`. `MCPSERVED_ADB_SERIAL`
selects a device when more than one is attached (a USB serial, or an `ip:port`);
`MCPSERVED_ADB` points at a non-PATH adb binary.

**Paired app (upgrade).** Install the app, enable the accessibility service, arm
it, and grant packages, then pair from the desktop's Pair screen (or
`mcpserved pair`). With a pairing on file the server uses the app automatically —
it finds the phone on the LAN over mDNS, or sets up the `adb forward` tunnel
itself, whichever answers first.

The headless server is the same binary: `mcpserved stdio` is what an AI host
spawns. `MCPSERVED_MODE` pins the backend to `adb` or `app`; the default `auto`
prefers the app and falls back.

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
app/      Android application — service, backends, grants, crypto, loopback server, UI
desktop/  Compose desktop app — GUI (pairing, discovery, one-click hosts) and the
          stdio MCP server, packaged as native installers for Windows/macOS/Linux
mcp/      Node reference server — the original TypeScript stdio implementation the
          Kotlin desktop side was ported from; kept as an executable spec
~~~

The desktop side lives in `desktop/` as a Compose for Desktop module. It shares
the sealed-frame wire format (X25519 / HKDF-SHA256 / ChaCha20-Poly1305) with the
Android app byte for byte, so the Kotlin `crypto/` package and the device's
`crypto/` package must stay in lockstep. `.github/workflows/desktop.yml` builds
the installers on a three-OS matrix (jpackage can only target its host).
