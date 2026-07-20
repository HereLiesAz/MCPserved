# Connect your AI — one-click setup

Getting a model to use MCPserved means registering the desktop server with the
**MCP host** the model runs in (Claude Desktop, Cursor, VS Code, and the like).
The host launches the server over stdio and exposes its tools to the model; the
model then drives your phone by calling those tools. You register it once.

There are two ways to do that in effectively one step: a **deep link** the host
opens and writes for you, or the **`mcpserved install`** command, which writes the
right config file for any supported host. Use whichever fits your host.

> One prerequisite the buttons can't do for you: the phone must be reachable by
> `adb` (`adb devices` shows it in state `device` — enable USB debugging, or
> `adb connect <ip>:5555` over Wi-Fi), and Node.js 20+ must be installed. The
> deep links below launch the server with `npx -y mcpserved`, so no manual build
> or global install is needed.

## The universal installer

One command registers the server with any host — it knows each host's config
path, JSON key, and entry shape, and merges a single entry without touching your
other servers:

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

`--npx` makes the written config launch via `npx -y mcpserved` (short,
self-updating) instead of this build's absolute path. If a host's config file
exists but isn't plain JSON, the installer refuses to rewrite it and prints the
snippet to paste instead.

## Per-host one-click

| Host | One-click method |
| --- | --- |
| **Cursor** | Open [**Add to Cursor**](cursor://anysphere.cursor-deeplink/mcp/install?name=mcpserved&config=eyJjb21tYW5kIjoibnB4IiwiYXJncyI6WyIteSIsIm1jcHNlcnZlZCJdfQ==), or `npx mcpserved install cursor`. |
| **VS Code** (Copilot) | Open [**Add to VS Code**](vscode:mcp/install?%7B%22name%22%3A%22mcpserved%22%2C%22command%22%3A%22npx%22%2C%22args%22%3A%5B%22-y%22%2C%22mcpserved%22%5D%7D), or `npx mcpserved install vscode`. Needs Copilot Agent mode with MCP. |
| **VS Code Insiders** | Open [**Add to VS Code Insiders**](vscode-insiders:mcp/install?%7B%22name%22%3A%22mcpserved%22%2C%22command%22%3A%22npx%22%2C%22args%22%3A%5B%22-y%22%2C%22mcpserved%22%5D%7D), or `npx mcpserved install vscode-insiders`. |
| **Claude Desktop** | No deep link — run `npx mcpserved install claude-desktop`, then restart Claude Desktop. |
| **Claude Code** | `npx mcpserved install claude-code` (runs `claude mcp add` for you), or directly: `claude mcp add mcpserved -s user -- npx -y mcpserved`. |
| **Windsurf** | `npx mcpserved install windsurf`. |
| **Cline** (VS Code) | `npx mcpserved install cline`. |

The deep links use the raw `npx -y mcpserved` launch, which assumes the package
is installed or fetchable from the npm registry. When running from a local
checkout instead, use `node dist/index.js install <host>` so the config points at
your build.

## What a written entry looks like

Most hosts read an `mcpServers` map; the entry is just a command and its args:

```json
{
  "mcpServers": {
    "mcpserved": {
      "command": "npx",
      "args": ["-y", "mcpserved"],
      "env": { "MCPSERVED_ADB_SERIAL": "192.168.1.5:5555" }
    }
  }
}
```

`env` is optional — include `MCPSERVED_ADB_SERIAL` only when more than one device
is attached. VS Code uses a `servers` map instead and wants `"type": "stdio"` on
the entry; the installer emits the right shape per host, so you rarely write this
by hand.

## After it's registered

Reload or restart the host, then just ask the model — for example, *"Use
mcpserved to open Settings and turn on Wi-Fi."* Under the hood the model calls
`capabilities`, then `session_begin`, then `ui_tree`, and acts from there. See
[mcp-tools](mcp-tools.md) for the full tool surface, and
[quickstart](quickstart.md) for the adb-vs-paired-app choice.

## Hosts that can't use this

MCPserved is a **local stdio** server that drives a phone attached to your
machine. Hosts that only accept **remote HTTP connectors** — the ChatGPT web and
desktop apps' custom connectors, and Google's Gemini — cannot launch a local
process, and exposing an adb-driving server at a public URL would defeat the
whole local-only design. Use one of the local MCP hosts above. If you need a
remote surface, that is a deliberate, separate piece of work (an authenticated
HTTP bridge), not a checkbox — and not something to point at your daily phone.
