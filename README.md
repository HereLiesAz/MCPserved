# MCPserved

An MCP server and Android application that let an authorized client observe and
control a phone, one granted package at a time.

The device decides. The MCP server carries sealed frames and holds no authority
of its own — it sits downstream of a language model's output, which makes it the
component least suited to being the thing that says yes.

## Shape

~~~
Claude <-> MCP server (stdio) <-> WSS relay <-> foreground service on the device
~~~

Both ends dial out. Carrier-grade NAT means the phone has no reachable address,
so the relay exists solely because two dialling peers need something in the
middle to be dialled at. It routes on an opaque device id, holds no keys, and
cannot read a frame it forwards.

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

`shell` is **omitted** from the tool manifest when no privileged backend exists,
not disabled. A tool that was never listed is not part of the world; a disabled
one invites a search for the way around it.

## Authorization

Per package, per scope, revocable, expiring by default.

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
window can change between check and act — a dialog appears, a notification takes
focus — and without it a tap aimed at a granted screen lands on the confirmation
button of something that was never granted, and returns success while doing it.

## Setup

**Device.** Sideload, enable the accessibility service, arm the service, grant
packages. Notification access is optional. Root is detected by running `su -c id`
rather than by looking for the binary, since Magisk hides itself from callers
that ask the naive way; there is a manual override for when it lies anyway.

**Server.**

~~~bash
cd mcp
npm install
npm run build
npx mcpserved pair
~~~

Pairing is a mutual QR exchange. Both public keys travel out of band in both
directions — routing the server's key through the relay would be more convenient
and would also let the relay substitute its own key during the one exchange that
establishes trust.

**Relay.**

~~~bash
cd relay
npx wrangler secret put FCM_SERVER_KEY
npx wrangler deploy
~~~

One Durable Object per device, using the WebSocket Hibernation API so an idle
room costs nothing.

## Known constraints

- **The screen stays on during a session.** Accessibility events stop and the
  node tree empties once the device locks, so a session on a dark screen fails
  every action while appearing merely unlucky. Keep the TTL short.
- **Shizuku dies on reboot.** Without root, every restart costs a manual re-pair.
- **Google is in the wake path.** FCM carries the signal to redial, never
  content. It sees that a device was asked to connect.
- **Not shippable on Play.** Accessibility-for-automation gets applications
  pulled. Sideload or F-Droid.
- **iOS is impossible.** No third-party iOS application can automate another —
  not native, not entitled, not with a paid account. The constraint is Apple's,
  not the web's.

## Not yet done

- `google-services.json` is absent. Add a Firebase project or strip the FCM
  dependency and lose the wake path.
- `MediaProjection` capture is declared as a capability but has no
  implementation; unrooted devices currently cannot screenshot at all.
- Launcher icon is the template's.
- No tests.

## Layout

~~~
app/     Android application — service, backends, grants, crypto, UI
relay/   Cloudflare Worker + Durable Object
mcp/     MCP server — stdio transport, tool schemas, relay client
~~~
