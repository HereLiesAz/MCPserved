# Wire protocol

The loopback protocol between the device (`transport/Protocol.kt`,
`transport/LocalServer.kt`) and the desktop app backend (`mcp/src/app-link.ts`,
`mcp/src/crypto.ts`). This describes the **app** backend only; the adb backend
synthesizes equivalent responses without any of this framing.

- **Version:** `PROTO_VERSION = 2`. Bumped in lockstep on both sides.
- **Transport:** a single TCP connection to `127.0.0.1:8790` (default) over
  `adb forward`. Newline-delimited JSON (NDJSON), one message per line.
- **Concurrency:** strictly single-flight, one connection at a time. There is a
  sequence number but no correlation id, so responses are matched to requests by
  **ordering** — valid only while exactly one request is outstanding. The device
  dispatches serially regardless.

## Connection lifecycle

```
server → device : Hello                (first line, plaintext JSON)
server → device : Envelope(Request)    (sealed)   ─┐
device → server : Envelope(Response)   (sealed)    │ repeat, single-flight
                                                   ─┘
```

1. The desktop server dials the loopback port and writes a `Hello` line carrying a
   fresh per-connection salt.
2. Both sides derive directional keys from the pairing secret + salt. The device
   drops the connection silently if the version mismatches, the salt is
   undecodable, or it is unpaired.
3. Every subsequent line is an `Envelope` whose `payload` is a sealed `Request`
   (server→device) or `Response` (device→server).
4. A frame that fails to open is **dropped without a reply** — answering would
   confirm to an unauthenticated sender that the device is here and which device it
   is.

## `Hello`

The first line the server sends. Plaintext JSON (no secret travels here — the salt
is public by design).

| Field | Type | Meaning |
| --- | --- | --- |
| `v` | int | Protocol version; must equal `2`, else the device drops the connection. |
| `salt` | string | Base64url per-connection salt folded into key derivation. Decoded with URL-safe, no-wrap base64. |

```json
{"v":2,"salt":"3Qm1o0mL8t2f9C1lqk9Xxw"}
```

## `Envelope`

Every sealed message on the wire.

| Field | Type | Meaning |
| --- | --- | --- |
| `deviceId` | string | Stable device identifier established at pairing. The receiver ignores envelopes whose `deviceId` does not match. |
| `seq` | long / number | Monotonic per-direction counter; also the AEAD nonce source. Sent as a JSON number by the server (decoded straight into a Long by the device). |
| `payload` | string | Base64 ChaCha20-Poly1305 ciphertext (with appended 16-byte tag) of a JSON `Request` or `Response`. |

```json
{"deviceId":"6f...","seq":0,"payload":"base64ciphertext=="}
```

The sealing (nonce layout, AAD = device id, monotonic replay check, directional
keys) is covered in [security](security.md).

## `Request`

A sealed, JSON-serialized `Request`. The JSON class discriminator is `"op"`.
The full set:

| `op` | Fields | Notes |
| --- | --- | --- |
| `capabilities` | — | Answerable without a session. |
| `session_begin` | `ttlSec` int = 300 | Clamped to 30–1800 on the device. Answerable without a session. |
| `session_end` | — | |
| `grants_list` | — | |
| `apps_list` | `grantedOnly` bool = true | |
| `ui_tree` | `maxDepth` int = 40 | |
| `screenshot` | `maxPx` int = 768 | |
| `notifications` | — | |
| `tap` | `nodeId` string?, `x` int?, `y` int? | |
| `long_press` | `nodeId`?, `x`?, `y`?, `ms` int = 500 | |
| `swipe` | `x1`, `y1`, `x2`, `y2` int, `ms` int = 300 | Coordinates required. |
| `scroll` | `nodeId` string, `dir` ScrollDir | Both required. |
| `type` | `text` string, `nodeId` string? | |
| `key` | `key` GlobalKey | |
| `launch` | `pkg` string | |
| `clipboard_get` | — | |
| `clipboard_set` | `text` string | |
| `shell` | `cmd` string | |

### Session gate

Only `capabilities` and `session_begin` are answerable without a live session;
every other request fails closed with `Err("no active session")`.

## `Response`

A sealed, JSON-serialized `Response`, discriminator `"op"`. Every response carries:

| Field | Type | Always present | Meaning |
| --- | --- | --- | --- |
| `ok` | bool | yes | Success. |
| `error` | string? | yes (nullable) | Failure reason when `ok` is false. |
| `foregroundChanged` | bool | yes | **True when the foreground package differed before and after the action** — invalidates every node id the caller holds. The caller's only reliable signal that an action landed somewhere other than where it was aimed. |

Response variants:

| `op` | Extra fields |
| --- | --- |
| `err` | (just the common fields; `ok=false`) |
| `ack` | (just the common fields; `ok=true`) — the generic success acknowledgement |
| `capabilities` | `caps: Set<Cap>`, `root: bool`, `shizuku: bool`, `a11y: bool` |
| `session` | `sessionId: string`, `expiresAtEpochMs: long` |
| `tree` | `pkg: string`, `activity: string?`, `nodes: List<UiNode>`, `pruned: int` |
| `image` | `mime: string`, `b64: string`, `w: int`, `h: int` |
| `apps` | `apps: List<AppEntry>` |
| `grants` | `grants: List<GrantEntry>` |
| `notifications` | `items: List<NotificationEntry>` |
| `text` | `text: string` — used for `clipboard_get` and `shell` |

A malformed or undispatchable request yields `Err`; a dispatch exception is caught
and returned as `Err(message)`.

## Data types

### `UiNode`

A single interactive or text-bearing node. Pure layout containers are dropped by
the `Pruner` (see below); the transmitted form is a **flat list** with `depth`
retained so the caller can reconstruct rough hierarchy cheaply.

| Field | Type | Meaning |
| --- | --- | --- |
| `id` | string | Stable across dumps for an unchanged layout; breaks on genuine layout change. See NodeId below. |
| `cls` | string | Simple class name, e.g. `Button`. |
| `bounds` | Bounds | Screen rectangle. |
| `depth` | int | Distance from the window root. |
| `text` | string? | Non-blank visible text. |
| `desc` | string? | Non-blank content description. |
| `clickable` | bool = false | |
| `editable` | bool = false | |
| `scrollable` | bool = false | |
| `checked` | bool? | Null unless the node is checkable. |
| `enabled` | bool = true | |

### `Bounds`

Screen-space rectangle in device pixels: `l`, `t`, `r`, `b` (int). Derived helpers
on the device: `centerX`, `centerY`, `width`, `height`, and `isDegenerate`
(width ≤ 0 or height ≤ 0 — such nodes are unhittable and pruned).

### `AppEntry`

`pkg: string`, `label: string`, `granted: bool`.

### `GrantEntry`

`pkg: string`, `scopes: Set<Scope>`, `expiresAtEpochMs: long?` (null = until
revoked).

### `NotificationEntry`

`pkg: string`, `key: string`, `title: string?`, `text: string?`,
`postedAtEpochMs: long`.

## Enums

| Enum | Values |
| --- | --- |
| `Cap` | `TREE`, `GESTURE`, `TEXT_INPUT`, `GLOBAL_KEYS`, `CAPTURE_SILENT`, `CAPTURE_PROJECTION`, `SHELL_ROOT`, `SHELL_SHIZUKU`, `NOTIFICATIONS`, `CLIPBOARD` |
| `Scope` | `OBSERVE`, `INTERACT`, `TYPE`, `LAUNCH`, `SHELL` |
| `GlobalKey` | `BACK`, `HOME`, `RECENTS`, `ENTER`, `DELETE`, `NOTIFICATIONS` |
| `ScrollDir` | `UP`, `DOWN`, `LEFT`, `RIGHT` |

See [backends](backends.md) for what each `Cap` means and [android-app](android-app.md)
for what each `Scope` permits.

## Node ids and pruning

- **NodeId** (`tree/NodeId.kt`) is a 12-char lowercase hex FNV-1a hash over the
  view's resource id, class, ordinal among siblings, and depth. **Screen
  coordinates are deliberately excluded**, so a node that scrolls keeps its
  identity while a node that moves because the layout changed gets a new id — a
  stale id that still resolves is worse than one that fails loudly. Not
  cryptographic; a collision costs a retry, not a breach.
- **Pruner** (`tree/Pruner.kt`) keeps a node only if it is visible, non-degenerate,
  and either interactive (clickable / long-clickable / editable / scrollable /
  checkable) or carrying text/description. Everything else is counted and dropped;
  a large `pruned` count hints the screen is canvas-drawn (prefer `screenshot`).
  The adb backend's `uiautomator` parser applies the same rule so the two trees
  match.
