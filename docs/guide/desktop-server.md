# Desktop MCP server

The `mcp/` package is a Node/TypeScript MCP server that speaks over **stdio** and
drives **one device per process**. Multiple devices would mean routing every call
by a target argument the model has to get right, and getting it wrong means acting
on the wrong phone — a failure with no recovery. Running a second server entry is
cheaper than a mistake of that shape.

- Package name: `mcpserved`, version `0.2.0`.
- Binary: `mcpserved` → `dist/index.js`.
- Requires Node `>= 20`.
- Dependencies: `@modelcontextprotocol/sdk`, `qrcode-terminal`.

## Install and build

From the repository:

```bash
cd mcp
npm install
npm run build          # tsc → dist/
```

Scripts:

| Script | Does |
| --- | --- |
| `npm run build` | Compiles TypeScript to `dist/`. |
| `npm start` | `node dist/index.js`. |
| `npm run pair` | `node dist/index.js pair` — the pairing flow. |

Or install globally:

```bash
npm i -g mcpserved
```

## Running

```bash
node dist/index.js         # or: mcpserved  (global install)
```

The process reads MCP requests on stdin and writes responses on stdout. **Nothing
but protocol messages may go to stdout** — anything else corrupts the stream, so
all diagnostics go to stderr. On `SIGINT`/`SIGTERM` it closes the link and exits.

Capabilities are queried **lazily**, on the first `ListTools` or `CallTool`, not
at startup. An MCP host may launch the process long before anything is asked of
it, and waking a phone to answer a question nobody posed wastes battery. If the
device is unreachable when tools are first resolved, the server advertises the
**unprivileged** surface (no `shell`) rather than an empty manifest, so a tool
can still be called and then explain what went wrong.

## Environment variables

| Variable | Default | Effect |
| --- | --- | --- |
| `MCPSERVED_MODE` | `auto` | Backend choice: `auto`, `adb`, or `app` (case-insensitive). See below. |
| `MCPSERVED_ADB_SERIAL` | unset | Selects a device when more than one is attached: a USB serial, or an `ip:port` such as `192.168.1.5:5555` for Wi-Fi. Unset uses adb's own default (fine with exactly one device). |
| `MCPSERVED_ADB` | `adb` | Path to the `adb` binary when it is not on `PATH`. |
| `MCPSERVED_PORT` | `8790` | Loopback port on the desktop that `adb forward` bridges to the device. Must match the device's `LocalServer.DEFAULT_PORT` (8790). Invalid values fall back to the default. Used only by the app backend. |

## The two backends behind one interface

Both backends implement the `Link` interface — a single `send(request, timeoutMs)`
plus `close()`. The tool surface is written entirely against `send`, so the tools
cannot tell them apart, which is the point: the model gets one consistent device
whether or not the app is installed.

| Backend | Source | How it answers |
| --- | --- | --- |
| `AppLink` | `app-link.ts` | Dials the device's loopback control port through an `adb forward` tunnel, sends **sealed** frames (ChaCha20-Poly1305), matches responses to requests by ordering. Single-flight. Derives fresh per-connection keys from a random salt in the opening hello. |
| `AdbLink` | `adb-link.ts` | Shells out to `adb` for every op — `input` for gestures and keys, `uiautomator dump` for the tree, `screencap` for pixels — and shapes each into the same response shape the app returns. Stateless: every call spawns adb afresh. |

`send` never rejects for an ordinary device-level refusal; those come back as
`{ ok: false, error }` so the model can reason about them. It rejects only for a
genuine transport failure the host should surface.

### AdbLink specifics

- Reports caps `TREE`, `GESTURE`, `TEXT_INPUT`, `GLOBAL_KEYS`, `CAPTURE_SILENT`,
  `NOTIFICATIONS`, `SHELL_SHIZUKU` (adb *is* a shell-level backend, so
  `shizuku: true` stands in for that ADB-level authority and the `shell` tool is
  offered), plus `SHELL_ROOT` when `su -c id` reports uid 0. Always `a11y: false`.
- `grants_list` returns an empty list; there is no per-app grant model over adb.
- `clipboard_get` / `clipboard_set` return an error pointing at the paired app.
- `session_begin` best-effort wakes the screen (`input keyevent 224`,
  `wm dismiss-keyguard`); the session id is the constant `"adb"`.
- `screenshot` returns the raw `screencap` PNG at native resolution — `maxPx` is
  not honored, there being no host-side image scaler.

## Backend selection

`chooseLink()` in `index.ts` decides:

```
mode = MCPSERVED_MODE (default "auto")
config = (mode == "adb") ? none : load ~/.config/mcpserved/pairing.json

if mode == "app" and no config:      error "run `npx mcpserved pair` first"
if config exists:
    probe the app (capabilities, 5s timeout)
    if it answers ok:                use AppLink (already connected)
    else if mode == "app":           error "app did not answer over adb-forward"
    else (auto):                     fall through
use AdbLink
```

| `MCPSERVED_MODE` | No pairing | Pairing, app answers | Pairing, app silent |
| --- | --- | --- | --- |
| `auto` (default) | adb | app | adb |
| `adb` | adb | adb | adb |
| `app` | **error** | app | **error** |

A pinned `app` mode never silently becomes adb: adb is device-wide shell
authority, and falling back to it would quietly widen what the operator asked to
restrict. See [security](security.md).

## Pairing flow

Run `npx mcpserved pair` (or `node dist/index.js pair`). See
[quickstart](quickstart.md) for the step-by-step and [security](security.md) for
what the exchange does and does not establish.

- The tool prompts for the device's payload string:
  `mcpserved:2:<deviceId>:<b64url pubkey>`. It rejects anything that is not a v2
  payload or whose key is not 32 bytes.
- It generates its own X25519 keypair, writes the pairing to
  `~/.config/mcpserved/pairing.json` (directory `0700`, file `0600`), and prints
  its own QR/string for the device to scan.
- Only raw keys are stored. The directional frame keys are derived per connection
  from a fresh salt, so there is nothing durable to store for them.
- The stored config is kept under the home directory, not alongside the code, so
  a checkout never contains key material and a stray `git add` cannot publish it.
