# Quickstart

Two paths. The **adb quick-connect** needs no app installed and gets a model
driving the phone in a few commands. The **paired-app upgrade** adds the
accessibility tree, per-package grants, and the notification mirror. Start with
the first; add the second when you want per-app authority.

## Prerequisites

- Node.js **20 or newer** on the desktop.
- `adb` (Android platform-tools) on the desktop, on `PATH` or pointed at by
  `MCPSERVED_ADB`.
- A phone with **USB debugging** enabled (Developer options), attached over USB —
  or reachable over adb-over-Wi-Fi.

## Path 1 — adb quick-connect

### 1. Reach the device with adb

Attach over USB and confirm the device is seen:

```bash
adb devices
```

Or pair over Wi-Fi (after enabling wireless debugging on the phone):

```bash
adb connect 192.168.1.5:5555
```

The device must report state `device` (not `unauthorized` or `offline`). Accept
the "Allow USB debugging?" prompt on the phone if it appears.

### 2. Install and run the desktop server

From the repository:

```bash
cd mcp
npm install
npm run build
node dist/index.js          # MCPSERVED_MODE defaults to auto → adb when unpaired
```

Or install the published package globally and run the `mcpserved` binary:

```bash
npm i -g mcpserved
mcpserved
```

With no pairing on file, `auto` falls back to the adb backend automatically.

If more than one device is attached, select one:

```bash
MCPSERVED_ADB_SERIAL=192.168.1.5:5555 node dist/index.js   # or a USB serial
```

### 3. Point an MCP host at it

The server speaks MCP over **stdio**. Configure your MCP host to launch it. The
fastest way is one command — `npx mcpserved install <host>` writes the right
config for you (or use the deep-link buttons); see [connect](connect.md) for
every host. To wire it by hand, a typical host config entry:

```json
{
  "mcpServers": {
    "mcpserved": {
      "command": "node",
      "args": ["/absolute/path/to/mcp/dist/index.js"],
      "env": { "MCPSERVED_ADB_SERIAL": "192.168.1.5:5555" }
    }
  }
}
```

or, with the global install, `"command": "mcpserved"` and no `args`.

The model should call `capabilities` first, then `session_begin`, then
`ui_tree`, and act from there. See [mcp-tools](mcp-tools.md).

> Over adb there is **no per-package grant model** — `adb` holds shell-level
> authority over the whole device, which is what enabling USB debugging
> conferred. That is disclosed in the capability report and the session notice.
> When per-app authority matters, use the paired app.

## Path 2 — paired-app upgrade (optional)

### 1. Install the app and clear the disclosure

Install the Android app and open it. The first screen is a prominent disclosure
that gates everything; accept it to proceed. See [android-app](android-app.md).

### 2. Enable the accessibility service

On the **Status** screen, tap **Open settings** next to Accessibility, and enable
MCPserved's accessibility service in system settings. Nothing observes or acts
without it. Optionally enable **Notification access** the same way for the
notification mirror.

### 3. Pair — a mutual QR exchange

Both public keys travel out of band, by QR, in both directions, so nothing sits
in the exchange that establishes trust. On the desktop:

```bash
npx mcpserved pair
```

The tool prompts for the device's pairing string. On the phone, open the **Pair**
screen and read the string under its QR code; paste it into the tool. The tool
then prints its own QR code — scan it on the phone's Pair screen with **Scan the
server's code**. The phone shows **Paired** once it has the key. The pairing is
written to `~/.config/mcpserved/pairing.json` on the desktop.

### 4. Arm and grant

- On **Status**, tap **Arm** to start the control service. It listens on the
  loopback port.
- On **Grants**, tap a package and choose its scopes (`OBSERVE`, `INTERACT`,
  `TYPE`, `LAUNCH`, `SHELL`) and an expiry. Nothing outside the grant table can
  be observed or touched.

### 5. Run

With a pairing on file and the device reachable, the server uses the app
automatically; it sets up the `adb forward` tunnel itself on connect. Just run:

```bash
node dist/index.js          # auto: prefers the app, falls back to adb
```

To require the app and never fall back:

```bash
MCPSERVED_MODE=app node dist/index.js
```

See [desktop-server](desktop-server.md) for backend selection and every
environment variable, and [troubleshooting](troubleshooting.md) when something
does not connect.
