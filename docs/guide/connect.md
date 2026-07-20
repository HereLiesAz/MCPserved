# Connect your AI

There are two ways to put a model in front of MCPserved. They differ in **what
acts as the MCP server**.

1. **Direct — the device is the MCP server.** The app runs an MCP server on the
   phone itself. A host connects straight to it over HTTP with a bearer token;
   nothing else runs on your computer but the one `adb forward` that bridges to
   the phone. This is the real thing — enforcement, tools, and the server all live
   on the device.
2. **Bridge — the desktop `mcpserved` server.** A small Node process on your
   computer speaks MCP to the host and drives the phone either over the app's
   sealed link or, with no app installed at all, straight over `adb`. Use it for
   the zero-install adb quick-connect, or for hosts that only launch stdio servers.

Either way the phone is where authority lives. Pick by what your host supports.

> Both paths need the phone reachable by `adb` — `adb devices` shows it in state
> `device` (enable USB debugging, or `adb connect <ip>:5555` over Wi-Fi).

## 1. Direct — connect a host to the device

The on-device server speaks MCP's Streamable HTTP. It binds to loopback on the
phone, so you bridge one port from your computer and point the host at it.

### Steps

1. **Install the app, clear the disclosure, enable the accessibility service, and
   Arm it** (see [android-app](android-app.md)). The MCP server runs while the app
   is armed.
2. **Get the endpoint and token.** On the app's **Pair** screen, under *Connect a
   model*, is the endpoint (`http://127.0.0.1:8791/mcp`) and a **bearer token**.
   Tap **Copy host config** for a ready-to-paste block, or **Copy token only**.
3. **Bridge the port** from your computer to the phone:

   ```bash
   adb forward tcp:8791 tcp:8791
   ```

4. **Point your host at it.** Hosts that accept a remote/HTTP MCP server with
   custom headers (Cursor, VS Code, Windsurf) take this directly:

   ```json
   {
     "mcpServers": {
       "mcpserved": {
         "url": "http://127.0.0.1:8791/mcp",
         "headers": { "Authorization": "Bearer <token from the app>" }
       }
     }
   }
   ```

   VS Code uses its `servers` map with `"type": "http"`; the shape is otherwise the
   same. **Rotate token** in the app mints a new one and invalidates the old.

### Hosts that only launch stdio servers

Claude Desktop and Claude Code connect to *stdio* servers, not HTTP URLs. Reach
the device's HTTP endpoint through the `mcp-remote` stdio↔HTTP shim:

```json
{
  "mcpServers": {
    "mcpserved": {
      "command": "npx",
      "args": [
        "-y", "mcp-remote", "http://127.0.0.1:8791/mcp",
        "--header", "Authorization: Bearer <token from the app>"
      ]
    }
  }
}
```

Or just use the bridge below — for these hosts it is often simpler.

## 2. Bridge — the desktop `mcpserved` server

The desktop server is the stdio MCP endpoint some hosts want, and the only path
that needs **no app installed** (it drives the phone straight over `adb`). One
command registers it with a host — it knows each host's config path, JSON key, and
entry shape, and merges without touching your other servers:

```bash
# from a checkout:
cd mcp && npm install && npm run build
node dist/index.js install            # interactive picker

# or, with the published package:
npx mcpserved install                 # interactive picker
npx mcpserved install cursor vscode   # name hosts directly
npx mcpserved install --all           # every supported host found
npx mcpserved install --print cursor  # just show the JSON, write nothing
```

If a host's config file exists but isn't plain JSON, the installer refuses to
rewrite it and prints the snippet to paste instead.

### Per-host one-click (bridge)

| Host | One-click |
| --- | --- |
| **Cursor** | [**Add to Cursor**](cursor://anysphere.cursor-deeplink/mcp/install?name=mcpserved&config=eyJjb21tYW5kIjoibnB4IiwiYXJncyI6WyIteSIsIm1jcHNlcnZlZCJdfQ==), or `npx mcpserved install cursor`. |
| **VS Code** (Copilot) | [**Add to VS Code**](vscode:mcp/install?%7B%22name%22%3A%22mcpserved%22%2C%22command%22%3A%22npx%22%2C%22args%22%3A%5B%22-y%22%2C%22mcpserved%22%5D%7D), or `npx mcpserved install vscode`. |
| **VS Code Insiders** | [**Add to VS Code Insiders**](vscode-insiders:mcp/install?%7B%22name%22%3A%22mcpserved%22%2C%22command%22%3A%22npx%22%2C%22args%22%3A%5B%22-y%22%2C%22mcpserved%22%5D%7D), or `npx mcpserved install vscode-insiders`. |
| **Claude Desktop** | `npx mcpserved install claude-desktop`, then restart it. |
| **Claude Code** | `npx mcpserved install claude-code`, or `claude mcp add mcpserved -s user -- npx -y mcpserved`. |
| **Windsurf** | `npx mcpserved install windsurf`. |
| **Cline** (VS Code) | `npx mcpserved install cline`. |

When you have the app installed and paired, the bridge upgrades to it
automatically (`MCPSERVED_MODE=auto`); without a pairing it falls back to adb. See
[desktop-server](desktop-server.md).

## After it's connected — either path

Reload or restart the host, then just ask the model — for example, *"Use mcpserved
to open Settings and turn on Wi-Fi."* Under the hood the model calls
`capabilities`, then `session_begin`, then `ui_tree`, and acts from there. The tool
surface is identical whichever path you took; see [mcp-tools](mcp-tools.md).

## Hosts that can't use this

MCPserved is a **local** MCP server — it drives a phone attached to your machine,
whether the server runs on the phone (direct) or on your computer (bridge). Hosts
that only accept **remote, public HTTP connectors** — the ChatGPT web and desktop
apps' custom connectors, and Google's Gemini — cannot launch a local process, and
the device's endpoint is bound to loopback for a reason: exposing an
adb-driving server at a public URL would defeat the whole local-only design. Use
one of the local hosts above. A public, authenticated bridge is a deliberate,
separate piece of work — not a checkbox, and not something to point at your daily
phone.
