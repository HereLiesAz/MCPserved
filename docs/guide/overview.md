# Overview

## What MCPserved is

MCPserved gives a language model hands on an Android phone. A model speaking the
[Model Context Protocol](https://modelcontextprotocol.io) calls tools — read the
screen, tap, type, launch an app — and those calls are carried to a real device
and executed there. The model sees one consistent device and a stable tool
surface; underneath, the work is done either by plain `adb` or by an on-device
application, and the tools cannot tell which.

## The local-first thesis

There is no relay and no cloud in the path, and no account to create. Control
travels a connection the user establishes themselves: a USB cable, or `adb`
over their own Wi-Fi. The Android app makes no outbound network connections and
sends screen contents to no server. The desktop server connects to nothing on
the internet either — in app mode it reaches the phone through a loopback tunnel,
in adb mode it shells out to the `adb` binary.

This is a deliberate inversion of the usual remote-control shape. Removing the
relay removes the remote-control profile that gets accessibility apps pulled from
app stores, and it removes the server that would otherwise be a standing target.
What remains is a pair of machines the user already controls, talking over a wire
the user already plugged in.

The desktop server **holds no authority of its own.** It sits downstream of a
language model's output, so it can be persuaded — which makes it exactly the
wrong place to enforce anything. In app mode it carries sealed frames to a device
that decides what to permit; in adb mode it is a thin shell over tools the user
already authorized by enabling USB debugging. Either way, the decision to say yes
lives on the device (paired app) or was made once by the user (adb).

## The two-part system

| Part | Directory | What it is |
| --- | --- | --- |
| Desktop MCP server | `mcp/` | A Node/TypeScript stdio MCP server. One device per process. Chooses a backend, exposes tools, carries requests. Not a Play app; a developer tool the user runs on their own computer. See [desktop-server](desktop-server.md). |
| Android application | `app/` | The on-device half: the accessibility service, the backends, the grant store, the crypto, the loopback control server, and the UI. See [android-app](android-app.md). |

The desktop server is the only thing the MCP host launches. The Android app is
optional — it is the upgrade, not a requirement.

## The two backends

Two backends sit behind one `Link` interface. The tool layer is written entirely
in terms of a single `send(request)` call, so a backend is free to satisfy it
however it likes.

| Backend | Needs | Gives |
| --- | --- | --- |
| **adb quick-connect** (`AdbLink`) | Only USB debugging (or adb-over-Wi-Fi) enabled. No app to install, no pairing. | Drives the device with `input`, `uiautomator dump`, and `screencap`, shaped into the same responses the app would return. Device-wide shell authority; **no** per-package grants, **no** clipboard, **no** honest notification list. |
| **paired app** (`AppLink`) | The app installed, the accessibility service enabled, the app armed, and a QR pairing on file. | The semantic accessibility tree, real text entry, per-package grants and scopes, the notification mirror, and clipboard access when a privileged backend is present. |

`MCPSERVED_MODE` pins the choice: `adb` forces the quick-connect path, `app`
forces the paired path (and fails loudly if no pairing is on file or the app
does not answer). The default, `auto`, prefers the app when a pairing exists and
the app answers, and falls back to adb otherwise. A pinned `app` mode never
silently becomes adb, because adb is device-wide authority and falling back to it
would quietly widen what the operator asked to restrict. See
[backends](backends.md) and [desktop-server](desktop-server.md).

## Who it's for

Developers, power users, and anyone building agentic workflows who wants a model
to operate a real Android device without handing that device to the cloud. It is
built for people comfortable with `adb`. It is not primarily a disability aid —
it uses the Accessibility API for control, and is disclosed as such rather than
claiming `isAccessibilityTool`.

## Known limits

- **iOS is impossible.** No third-party iOS application can automate another.
- **`MediaProjection` capture is unimplemented.** It is declared as a capability
  (`CAPTURE_PROJECTION`) but has no code behind it, so an unrooted device cannot
  screenshot *through the app*. The adb backend's `screencap` works regardless.
  See [backends](backends.md).
- **The screen stays on during an app session** and **Shizuku dies on reboot** —
  see [android-app](android-app.md) and [troubleshooting](troubleshooting.md).
