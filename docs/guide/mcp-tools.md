# MCP tools

The tool surface exposed to the model, derived from `mcp/src/tools.ts` and mirrored
by the device's `Request` protocol (`transport/Protocol.kt`). Both backends answer
the same tools, so the model gets one consistent device.

Two rules shape the surface:

- **The list is built after the device reports its capabilities.** An operation
  the device cannot perform is *absent*, not present-and-failing — a tool that was
  never listed is simply not part of the model's world. In practice only `shell`
  is conditional; everything else is always listed.
- **Results are content, never protocol errors.** A denied grant or a lost
  accessibility binding is information the model needs to change course, returned
  as `error: <reason>` text. Only a genuine transport failure surfaces as an MCP
  error.

## Availability

| Tool | Availability |
| --- | --- |
| All tools below except `shell` | Always listed. |
| `shell` | **Only** when a privileged backend exists (`root` or `shizuku` true). Over adb, `shizuku` is reported true, so `shell` is offered. |

If the device is unreachable when tools are first resolved, the unprivileged
surface (no `shell`) is advertised so a call can still explain the failure.

## Session and inventory tools

| Tool | Purpose | Inputs | Notes |
| --- | --- | --- | --- |
| `capabilities` | Report which control backends are available. Call first if anything behaves unexpectedly. | none | Returns accessibility/root/shizuku status and the cap list. Answerable without a session. |
| `session_begin` | Open a control session. **Required before any other operation** (except `capabilities`). | `ttlSec` int, 30–1800, default 300 | Device holds its screen awake for the duration. Keep the TTL short. |
| `session_end` | Close the session and release the screen hold. | none | Call when done rather than letting it expire. |
| `grants_list` | List which packages are authorized and with what scopes. | none | Empty over adb (no grant model). Nothing outside this list can be touched. |
| `apps_list` | List installed applications. | `grantedOnly` bool, default **true** | Default lists authorized packages only; `false` lists every launchable app — disclosure the task rarely needs. |

## Observation tools

| Tool | Purpose | Inputs | Notes |
| --- | --- | --- | --- |
| `ui_tree` | Read the current screen as an indented node tree. **The primary way to see the device** — prefer over `screenshot`. | `maxDepth` int, 1–100, default 40 | Requires `OBSERVE`. Node ids survive scrolling but not layout changes. Returns package, node count, pruned count, and the tree. |
| `screenshot` | Capture the screen as an image. | `maxPx` int, 256–2048, default 768 | Requires `OBSERVE`. Use **only** when `ui_tree` returns no addressable nodes (games, canvas UI, some WebViews). Over adb, returned at native resolution (maxPx ignored); through the app, needs root (MediaProjection is unimplemented). |
| `notifications` | Read the notification shade, filtered to authorized packages. | none | Requires `OBSERVE` and, in app mode, notification access. Over adb it is a heuristic `dumpsys` parse. |

## Interaction tools

Every mutating tool is grant-bracketed on the device and its response may carry a
foreground-change warning — see [Foreground changes](#foreground-changes).

| Tool | Purpose | Inputs | Scope |
| --- | --- | --- | --- |
| `tap` | Tap a node by id, or a raw coordinate. Prefer the node id. | `nodeId` string; or `x`, `y` int | `INTERACT` |
| `long_press` | Press and hold a node or coordinate. | `nodeId`; or `x`, `y`; `ms` int (default 500) | `INTERACT` |
| `swipe` | Swipe between two coordinates. For lists prefer `scroll`. | `x1`, `y1`, `x2`, `y2` int (required); `ms` (default 300) | `INTERACT` |
| `scroll` | Scroll a scrollable node in a direction. | `nodeId` (a `[scroll]` node), `dir` ∈ {UP, DOWN, LEFT, RIGHT} (both required) | `INTERACT` |
| `type` | Type text into an editable field. Targets the focused field when no id is given. | `text` string (required); `nodeId` (a `[edit]` node) | `TYPE` |
| `key` | Press a global key. | `key` ∈ {BACK, HOME, RECENTS, ENTER, DELETE, NOTIFICATIONS} (required) | `INTERACT` |
| `launch` | Bring an application to the foreground. | `pkg` string (required) | `LAUNCH` — **of the target**, not the current app |

`tap`, `long_press`, and coordinate resolution: a `nodeId` is resolved to a screen
point on the device (app mode) or from the last `ui_tree`'s cached centres (adb).
Coordinates computed from an older tree will miss after any scroll.

## Clipboard tools

| Tool | Purpose | Inputs | Notes |
| --- | --- | --- | --- |
| `clipboard_get` | Read the clipboard. | none | Requires `OBSERVE`. Needs root or Shizuku — Android forbids background clipboard reads. **Unavailable over adb.** |
| `clipboard_set` | Write the clipboard. | `text` string (required) | Requires `TYPE`. App tries the native `ClipboardManager` first, falls back to shell. **Unavailable over adb.** |

## Privileged tool

| Tool | Purpose | Inputs | Notes |
| --- | --- | --- | --- |
| `shell` | Run a shell command. Reaches every package at once, so it is logged and bracketed like any other action. | `cmd` string (required) | Requires a `SHELL` grant (app mode). Listed **only** with a privileged backend. Over adb it runs under adb's device-wide shell authority. |

## Foreground changes

Any mutating response may end with:

```
WARNING: the foreground app changed during this action.
All node ids are stale — call ui_tree before doing anything else.
```

This means the window moved between the permission check and the action, so every
node id the model is holding now refers to a layout that is gone. It is the single
most load-bearing line in a response — heed it before the next action. See
[android-app](android-app.md) (Enforcer bracketing) and [protocol](protocol.md).
