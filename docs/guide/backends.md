# Backends

A backend is one way of touching the device. **Backends are not tiers.** Root is
not uniformly better than accessibility: `su -c input tap` spawns a process per
gesture and lands roughly 200 ms behind `dispatchGesture`, while root is the only
path to a screenshot that skips the MediaProjection dialog. The resolver therefore
dispatches **per operation**, not per privilege level.

There are two layers to keep separate:

- Inside the **paired app**, a `Resolver` routes each operation across three
  on-device backends: **Accessibility**, **root**, and **Shizuku**.
- The **desktop server** chooses between the whole paired app and the **pure-adb**
  backend (`AdbLink`). See [desktop-server](desktop-server.md).

This page covers both.

## The on-device backends

Every backend implements `ControlBackend`. Every method returns `Result`; an
unsupported operation *fails* rather than throwing, so a partially-capable device
degrades instead of crashing. Each backend advertises a capability set (`caps`)
probed at startup.

### Accessibility (`A11yBackend`)

Present whenever the accessibility service is bound. The preferred path for
observation and gestures regardless of root.

- **Caps:** `TREE`, `GESTURE`, `TEXT_INPUT`, `GLOBAL_KEYS`.
- Gestures go through `dispatchGesture`, which reports genuine completion or
  cancellation (a cancelled gesture — usually another window stealing touch — is a
  real failure, not a success).
- `scroll` prefers the node's own scroll action (respects nested scrolling), and
  falls back to a synthetic swipe over the node's bounds.
- `type` uses `ACTION_SET_TEXT` on the addressed or focused editable node.
- **Cannot** capture the screen, run shell, launch apps, or reach the `ENTER` /
  `DELETE` keys — those return `unsupported` and route to a privileged backend.

### Root (`RootBackend`)

Root-backed control via a persistent `su` shell.

- **Caps (when available):** `SHELL_ROOT`, `CAPTURE_SILENT`, `GESTURE`,
  `TEXT_INPUT`, `GLOBAL_KEYS`, `CLIPBOARD`.
- Availability is probed **once**, at construction, by running `su -c id` and
  checking for `uid=0` — not by looking for the `su` binary, which Magisk hides.
  The probe runs on a background thread (caps report unrooted until it resolves).
- `capture` uses `screencap -p`, decodes, downscales to `maxPx`, and re-encodes as
  JPEG — the single clearest reason to prefer root, since it needs no
  MediaProjection consent dialog.
- Does **not** implement `tree` (uiautomator dump is slower and yields less than
  the accessibility tree) or `scroll` (needs a resolvable node id).
- `type` rejects a `nodeId` — `input text` cannot address a specific node, and
  typing into the wrong field looks like success until much later.

### Shizuku (`ShizukuBackend`)

ADB-level control via Shizuku, for unrooted devices. Reaches everything
`adb shell` reaches — `input`, `am`, `pm`, `settings` — and nothing beyond it.

- **Caps (when available):** `SHELL_SHIZUKU`, `GESTURE`, `TEXT_INPUT`,
  `GLOBAL_KEYS`, `CLIPBOARD`.
- Available only when the reflected `Shizuku.newProcess` resolves, the binder
  pings, and the permission is granted. Reached by reflection and guarded: an
  upstream signature change degrades the backend to unavailable rather than
  crashing.
- **No capture:** shell uid cannot read the framebuffer on modern releases, so it
  advertises no capture capability and screenshots fall through to MediaProjection
  (which is unimplemented — see below).
- Does not implement `tree` or `scroll`; `type` rejects a `nodeId`.
- **Shizuku dies on reboot.** Without root, every restart costs a manual re-pair;
  the backend reports unavailability cleanly rather than failing per-call.

## The per-operation resolver

`Resolver` routes each operation down a preference list, trying each backend that
advertises the needed cap until one returns success. A `Result.failure` from a
capable backend is **not** retried on a lesser one — a rejected gesture means the
window refused it, and repeating through a coarser path would land the touch
somewhere unintended.

Construction order encodes preference. `ControlService` builds the list as
**Accessibility, root, Shizuku**, so observation and gestures resolve to
accessibility even on a rooted device.

| Operation | Preference chain (caps, in order) |
| --- | --- |
| `tree` | `TREE` |
| `foregroundPackage` / `foregroundActivity` | `TREE`, `SHELL_ROOT`, `SHELL_SHIZUKU` |
| `tap` / `longPress` / `swipe` | `GESTURE`, `SHELL_ROOT`, `SHELL_SHIZUKU` |
| `scroll` | `TREE` (accessibility-only — needs a resolvable node id) |
| `type` | `TEXT_INPUT`, `SHELL_ROOT`, `SHELL_SHIZUKU` |
| `key` | `GLOBAL_KEYS`, `SHELL_ROOT`, `SHELL_SHIZUKU` |
| `launch` | `SHELL_ROOT`, `SHELL_SHIZUKU` (accessibility has no launch path) |
| `capture` | `CAPTURE_SILENT`, `CAPTURE_PROJECTION` |
| `shell` | `SHELL_ROOT`, `SHELL_SHIZUKU` |

Accessibility is authoritative for the foreground package: its value comes from a
window-state event, not a poll, so it cannot report a package that was foreground
only at the moment of asking. The `dumpsys` fallbacks exist for when the service
has been torn down mid-session.

The device's reported `root` / `shizuku` / `a11y` booleans (and the union of all
caps) come from this resolver and shape the desktop tool list.

## The pure-adb backend (`AdbLink`)

When the app is not used, the desktop server drives the device entirely through
`adb`, synthesizing the same protocol responses the app would return.

- `input tap/swipe` for gestures and keys; `uiautomator dump` for the tree
  (parsed and pruned to match what the on-device `Pruner` does); `screencap -p`
  for pixels.
- Reports caps `TREE`, `GESTURE`, `TEXT_INPUT`, `GLOBAL_KEYS`, `CAPTURE_SILENT`,
  `NOTIFICATIONS`, `SHELL_SHIZUKU`, plus `SHELL_ROOT` when `su -c id` is uid 0.
  `a11y` is always false; `shizuku: true` stands in for adb's own shell authority
  so the `shell` tool is offered.

What adb **cannot** honestly do, it says so about rather than faking:

| Limit | Detail |
| --- | --- |
| **No per-app grants** | `grants_list` is empty. adb holds shell-level authority over the whole device — exactly what enabling USB debugging conferred. Disclosed, not narrowed. |
| **No clipboard** | `clipboard_get` / `clipboard_set` return an error pointing at the paired app. |
| **No honest notification list** | `notifications` is a best-effort heuristic parse of `dumpsys notification`; the app's `NotificationListener` is the honest path. |
| **ASCII-ish text** | `input text` maps spaces to `%s` but cannot express newlines or most non-ASCII. Good enough for field entry; the app's text backend handles the rest. |
| **No screenshot scaling** | `maxPx` is ignored; the raw native-resolution PNG is returned. |

## The capability model

Capabilities (`Cap`) are advertised so the caller never probes blindly. The
desktop server turns them into the tool list: the `shell` tool appears **only**
when a privileged backend exists (`root || shizuku`); everything else is always
listed. See [mcp-tools](mcp-tools.md) and [protocol](protocol.md).

| Cap | Meaning |
| --- | --- |
| `TREE` | Accessibility connected; semantic node tree available. |
| `GESTURE` | Tap / long-press / swipe dispatch. |
| `TEXT_INPUT` | Text entry. |
| `GLOBAL_KEYS` | Global keys (BACK, HOME, RECENTS, NOTIFICATIONS; ENTER/DELETE need a shell backend). |
| `CAPTURE_SILENT` | Screen capture without a MediaProjection dialog (root `screencap`). |
| `CAPTURE_PROJECTION` | Capture via MediaProjection. **Declared but unimplemented** — no code behind it. |
| `SHELL_ROOT` | Arbitrary shell via root. |
| `SHELL_SHIZUKU` | Arbitrary shell via Shizuku (ADB-level, no root). |
| `NOTIFICATIONS` | Notification shade access. |
| `CLIPBOARD` | Clipboard access (privileged; Android forbids background clipboard reads). |

> **MediaProjection is unimplemented.** `CAPTURE_PROJECTION` is advertised but has
> no implementation, so an **unrooted device cannot screenshot through the app**.
> The adb backend's `screencap` works regardless.
