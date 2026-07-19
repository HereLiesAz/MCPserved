# Troubleshooting

Common failures and their fixes. Most silent failures are explained by the
capability report — call the `capabilities` tool first when anything behaves
unexpectedly.

## adb not found

**Symptom:** `adb not found on PATH — install platform-tools, or set MCPSERVED_ADB
to the adb binary` (the backend maps `ENOENT` to this).

**Fix:** install Android platform-tools and put `adb` on `PATH`, or point at it:

```bash
MCPSERVED_ADB=/opt/platform-tools/adb node dist/index.js
```

## No device

**Symptom:** `no adb device — attach over USB and run 'adb devices', or 'adb
connect <ip>:5555' for Wi-Fi`. The server reports readiness only when exactly one
target reports state `device`.

**Fix:**

```bash
adb devices                       # must show a device in state 'device'
adb connect 192.168.1.5:5555      # for adb-over-Wi-Fi
```

Accept the "Allow USB debugging?" prompt on the phone. If several devices are
attached, select one:

```bash
MCPSERVED_ADB_SERIAL=192.168.1.5:5555 node dist/index.js
```

## App not reachable over adb-forward

**Symptom (auto mode):** the server silently falls back to adb. **Symptom (app
mode):** `MCPSERVED_MODE=app, but the on-device app did not answer over
adb-forward. Check that it is installed, paired, and armed, and that the device is
reachable`.

**Checklist:**

- The app is **armed** (Status screen → Arm; the ongoing notification is visible).
- The device is **paired** (Pair screen shows **Paired**) and the desktop has a
  pairing at `~/.config/mcpserved/pairing.json`.
- The device is reachable (`adb devices`).
- The port matches: the device listens on `8790`; `MCPSERVED_PORT` must match if
  set. The server sets up the `adb forward` itself, but a stale forward to a dead
  port can linger — `adb forward --remove-all` clears it.
- Only one controller is served at a time; a second connection waits in the
  backlog.

## Session fails on a locked screen

**Symptom:** actions fail or the tree is empty, seemingly at random, in the app
backend.

**Cause:** once the device locks, accessibility events stop and
`rootInActiveWindow` returns null — the tree empties and every action fails while
appearing merely unlucky.

**Fix:** the app holds the screen awake for the session's duration, but keep the
**TTL short** and do not let a session idle. If the screen still locks (an OEM
power manager overriding the hold), keep the device on the charger and unlocked
during a session. Over adb, `session_begin` best-effort wakes and dismisses the
keyguard, but a secured lock screen will still block input.

## Shizuku lost after reboot

**Symptom:** the app came back after a reboot with a narrower capability set;
`shell`, gestures-via-shell, and clipboard are gone on an unrooted device.

**Cause:** Shizuku is bound to a service started by pairing over wireless
debugging, and that service **dies on reboot**. This is reported honestly through
the capability query rather than as per-call failures.

**Fix:** restart Shizuku and re-pair it (its own app / `adb` procedure), then the
backend returns. On a rooted device this does not apply. See
[backends](backends.md).

## Text with non-ASCII

**Symptom:** typed text loses newlines or non-ASCII characters.

**Cause:** shell-based `input text` (the adb backend, and the app's root/Shizuku
text path) maps spaces to `%s` but cannot express newlines or most non-ASCII.

**Fix:** use the **paired app** with the accessibility backend — its `type` uses
`ACTION_SET_TEXT`, which handles the full string. This is one of the reasons to
prefer the app over adb for text entry.

## Clipboard or notifications unavailable over adb

**Symptom:** `clipboard is not available over adb — pair the on-device app for
clipboard access`; or notifications come back thin / empty.

**Cause:** the adb backend has no clipboard access and only a heuristic `dumpsys`
notification parse. These operations need the app.

**Fix:** use the paired app. Note that even there, `clipboard_get` needs a
privileged backend (root or Shizuku), since Android forbids background clipboard
reads; and `notifications` needs notification access enabled (Status screen).

## MODE=app with no pairing

**Symptom:** `MCPSERVED_MODE=app, but no pairing was found. Run 'npx mcpserved
pair' first.`

**Cause:** `app` mode is pinned but there is no pairing on file. A pinned `app`
mode never silently falls back to adb, because adb is device-wide authority.

**Fix:** run the pairing flow, or drop to `auto`:

```bash
npx mcpserved pair            # then arm and grant on the device
# or
MCPSERVED_MODE=auto node dist/index.js
```

## Nothing is authorized (app backend)

**Symptom:** every operation returns `denied: <SCOPE> not granted for <pkg>`, or
`grants_list` is empty.

**Cause:** the grant table is empty (the correct resting state) or the foreground
package has no grant for the scope the operation needs.

**Fix:** on the **Grants** screen, authorize the target package with the needed
scopes. Remember grants **expire** (default 1 hour) and that `launch` checks the
**target's** grant, not the current app's. See [android-app](android-app.md).

## Screenshot fails through the app on an unrooted device

**Symptom:** `screenshot` errors in the app backend on a device without root.

**Cause:** `MediaProjection` capture (`CAPTURE_PROJECTION`) is **declared but
unimplemented**, and Shizuku's shell uid cannot read the framebuffer. Only root's
silent `screencap` works through the app.

**Fix:** use `ui_tree` instead (preferred anyway), or use the **adb** backend,
whose `screencap` works regardless of root. See [backends](backends.md).
