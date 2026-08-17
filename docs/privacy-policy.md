# Privacy Policy

**MCPserved**

_Last updated: 2026-07-20_

MCPserved is a local-first device-control tool. It is built so that there is
almost nothing to have a privacy policy about: the app collects no personal
data, has no accounts, and contains no analytics or advertising. By default it
makes no connection to any server operated by us or anyone else. Two optional,
off-by-default features change what "by default" means, and this document says
so precisely, in the "Remote access (optional)" section below.

## Summary

- **We collect nothing.** No personal data, no usage analytics, no identifiers.
- **We share nothing.** There is no backend to share it with.
- **By default, nothing leaves your device except to a client you connect
  yourself,** over a local link (USB, or adb over your own network) that you
  set up.
- **You can opt into two remote-access features,** each off until you
  explicitly turn it on and accept a separate disclosure — see "Remote access
  (optional)" below.
- **You are in control.** The app does nothing until you enable it, pair a
  client, arm it, and grant specific apps — each of which you can undo.

## What the app accesses, and why

MCPserved lets a client you pair — a program running on your own computer — read
your screen and perform taps, typing, and swipes on your behalf. To do this it
uses these capabilities:

| Capability | Why | Where it goes |
| --- | --- | --- |
| **Accessibility Service** (screen content + input dispatch) | The core function: reading the current screen and carrying out the actions your paired client requests, only for apps you have granted. | Screen content is relayed only to your paired local client over the local connection. It is not stored by the app and not sent to any server. |
| **Installed apps list** | To show you the list of apps on the Grants screen so you can choose which to authorize. | Stays on the device; used only to populate that screen. |
| **Notification access** (optional) | To mirror notifications to your paired client when you enable it. | Same local connection only; never persisted or uploaded. |
| **Camera** (optional) | To scan the pairing QR code shown by the desktop server. | Used only on the pairing screen; no images are stored or transmitted. |

## Data storage

- **Pairing keys** are generated on the device and stored in Android's encrypted
  storage (`EncryptedSharedPreferences`). They never leave the device. You can
  destroy them at any time by rotating the identity in the app.
- **Grants** (which apps you authorized, and with what scopes) are stored locally
  on the device. You can revoke them individually or all at once.
- **Session activity** is logged only in memory for the duration of an open
  session, shown to you, and cleared when the session ends.

The app keeps no other records.

## Network

By default, MCPserved makes **no outbound internet connections.** Its control
channel is a listener bound to the loopback address `127.0.0.1`, reachable only
through an `adb` connection you establish yourself (a USB cable, or
adb-over-Wi-Fi on your own network). The `INTERNET` permission is present only
because Android requires it to open any socket, including a loopback one; by
default it is not used to contact any server.

The companion desktop server is a separate open-source program that runs on your
own computer and communicates with the device over that same local connection. It
is not part of this app and also contacts no server of ours by default.

## Remote access (optional)

Two features, both off until you turn them on and accept a one-time disclosure
shown in-app first, let the device be reached beyond the local connection
above:

- **A wider bind for a private mesh.** The on-device MCP endpoint can bind
  every network interface instead of loopback only, for use behind a private,
  encrypted mesh (Tailscale, WireGuard) that you install and join yourself.
  MCPserved does not create, operate, or connect to such a mesh — it only
  changes which interface its own socket accepts connections on. It is still
  protected by the same secret token as the local-only default.
- **A relay you point it at.** The device can dial out to a relay server —
  yours, or a third party's — over a single outbound connection. What it sends
  is the same protocol frames the local connection already uses, sealed with
  a key that is generated on your device and never leaves it; the relay
  forwards ciphertext it cannot read, whether you operate it yourself or a
  third party does. See `relay/README.md` in the project repository for how
  that server works and what it can and cannot see.

Turning either of these on is your decision, made explicitly, after reading
what it does. Turning them off returns the app to the local-only default
above.

## The local connection and its authority

When you pair a client and open a session, that client can read the screen and
act on the apps you have granted, for the duration of the session. This is the
app's purpose, and it is entirely under your control:

- Nothing is reachable until you enable the accessibility service, pair a client,
  arm the app, and grant specific apps.
- Every session is time-limited and shown in an ongoing notification with a stop
  control.
- If you connect over adb-over-Wi-Fi, treat your network as you would for any adb
  session; use USB if you prefer not to.

## Children

MCPserved is a developer tool and is not directed to children.

## Changes

If this policy changes, the updated version will be posted here with a new "last
updated" date.

## Contact

Questions about this policy or the app can be sent to the project maintainer via
the repository: <https://github.com/HereLiesAz/MCPserved>.
