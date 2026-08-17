# Remote access (opt-in)

Everything else in this guide assumes the model's host and the phone share a
network path — a USB cable, or `adb` over the same Wi-Fi. That's the default,
and it stays the default: nothing here changes unless the operator explicitly
turns it on, and all three paths are gated behind a prominent, one-time
disclosure in the app before the toggle takes effect (see
[android-app](android-app.md)).

This page is for the case that default doesn't cover: a host with **no local
network path to the phone at all** — a cloud-hosted AI session, most notably.

## Three paths

The obvious asks — "let me use Tailscale," "let me use a public tunnel," "let
me run my own relay," "let me skip all that and just be reachable" — turn out
to be three engineering problems, not four, once you separate them by which
of the phone's two servers they extend:

- **`McpServer`** (the direct MCP-over-HTTP endpoint, port 8791) authenticates
  with a bearer token over **plaintext** HTTP. Fine on loopback, where nothing
  off-device can even reach the socket. Not fine handed to a third party, and
  not fine exposed directly to the open internet either.
- **`LocalServer`** (the sealed-frame protocol the desktop bridge and the app
  backend speak, port 8790) is **already end-to-end encrypted** — X25519
  pairing, per-connection ChaCha20-Poly1305 frames — regardless of transport.

So:

| Path | Extends | Serves |
| --- | --- | --- |
| **Wider bind** | `McpServer` | "Let me use Tailscale/WireGuard" |
| **UPnP** | `LocalServer`'s sealed-frame protocol | "Let me skip hosting anything and just be reachable" — no relay, no third party, works only on a Wi-Fi network whose router supports it |
| **Relay** | `LocalServer`'s sealed-frame protocol | "Let me use a public tunnel" and "let me run my own relay" — the same client/server code, differing only in who operates the relay and how its URL is reached; the only one of the three that also works on cellular |

`McpServer` never gets a UPnP or relay path. Routing its plaintext-behind-a-
token protocol through a third party, or exposing it straight to the internet
via a port mapping, would hand whoever finds it your taps, typed text, and
shell output in the clear — the opposite of what either mechanism should buy
you. If you want to be reachable without hosting anything, or via a relay, it
carries the already-encrypted protocol; if you want a wider bind, pair it with
a private mesh you trust.

## Path 1: wider bind, for a private mesh

Turn on **Bind for a private mesh** on the app's "Connect a model" screen (or
set `RemoteAccessStore.wildcardMcpBind` directly). `McpServer` binds `0.0.0.0`
instead of `127.0.0.1` — reachable on any interface the phone has, including a
Tailscale or WireGuard virtual interface if you've installed and joined one
separately. MCPserved does not create, manage, or depend on any specific mesh
technology; it only widens which interface its own socket accepts on.

Then point a host at the phone's mesh address instead of `127.0.0.1`, same as
any direct connection:

```json
{
  "mcpServers": {
    "mcpserved": {
      "url": "http://100.x.x.x:8791/mcp",
      "headers": { "Authorization": "Bearer <token from the app>" }
    }
  }
}
```

**The bearer token is still the only thing standing between a request and an
answer.** Inside a private, WireGuard-encrypted mesh, that's fine — nobody
outside the mesh can even reach the packets. On an open or shared LAN, it
hands anyone on that network a login-free shot at guessing the token. Turn
this on only behind a mesh you trust.

## Path 2: UPnP, for zero hosting

Turn on **Try to open a port automatically (UPnP)** on the same screen. The
device asks any UPnP IGD-capable router on the network to forward an external
port straight to `LocalServer`'s port — no relay to run or point at, no third
party in the data path at all once the mapping is live. The screen shows the
resulting `address:port` once mapped; it's rechecked every few minutes since
routers can move the mapping or reassign the external address.

This is standard practice on Android — torrent clients, DLNA/media servers,
and game networking libraries all do exactly this for the common case of a
home Wi-Fi router that supports it. It has real limits, though: it only works
on Wi-Fi (cellular has no router to ask), some home routers ship with UPnP
disabled, and the address it hands you isn't stable across router reboots or
DHCP churn. If either of those matters to you — cellular reachability, or a
stable address — use the relay path below instead.

Point a host at whatever address the app shows, same shape as a direct
connection but over the sealed-frame protocol rather than plaintext MCP —
consult the desktop bridge or `mcp/`'s relay/direct client config for how to
hand a host an explicit `host:port` outside adb-forward's usual localhost
tunnel.

## Path 3: relay, for everything else

Turn on **Dial out to a relay**, set a relay URL, and copy the room token —
all on the same screen. The device opens one outbound WebSocket connection to
that URL and dials the sealed-frame protocol over it, playing the same
"device" role it always plays; nothing about the crypto handshake changes.

On the host side, `mcpserved`'s stdio server picks this up via three
environment variables:

```bash
MCPSERVED_MODE=relay \
MCPSERVED_RELAY_URL=wss://your-relay.example.com \
MCPSERVED_RELAY_ROOM=<room token from the app> \
mcpserved install claude-code
# or: claude mcp add mcpserved -s user -- npx -y mcpserved
```

`mcpserved install`/`connect` bake these into the registered host config the
same way they already bake in `MCPSERVED_MODE=adb`, so this is a one-time
setup, not something you re-type per session.

### Running a relay

The relay itself (`relay/`, this repo) is a small, deliberately dumb WebSocket
server: it pairs one "device" connection and one "host" connection by room
token and forwards bytes verbatim, never parsing or logging a payload. See
`relay/README.md` for running it — a single `npm start` locally, a Docker
container, or fronted by Cloudflare Tunnel or ngrok for a free public HTTPS
URL with automatic TLS. Because the relay only ever forwards already-sealed
ciphertext, fronting it with a third-party tunnel exposes that third party to
opaque bytes, not to your phone's screen contents or typed input.

**Whose relay should you use?** One you run yourself is the safest bet — you
already trust your own infrastructure. A relay run by someone else can occupy
your device's room slot with a bogus connection (denial of service on that
room; the fix is rotating the room token) or watch ciphertext go by, but
cannot decrypt it and cannot impersonate either end without the X25519 pairing
secret, which never travels anywhere near the relay.

## What none of the three paths change

All three are new *transports*. None is a new *authority* — every request
that arrives over any of them still passes through the same `Enforcer.guard`
bracketing, the same per-package grant table, and the same session gate as a
request arriving over `adb forward`. See [security](security.md)'s "Trust
boundaries" table: the relay is untrusted by construction, exactly like the
desktop MCP server always was, because it sits downstream of a language
model's output and carries frames without deciding anything. A live UPnP
mapping is untrusted the same way, just without an intermediary: it makes the
already-authenticated port reachable by more people, not by anyone with more
authority once they get there.
