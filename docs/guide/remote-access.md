# Remote access (opt-in)

Everything else in this guide assumes the model's host and the phone share a
network path — a USB cable, or `adb` over the same Wi-Fi. That's the default,
and it stays the default: nothing here changes unless the operator explicitly
turns it on, and every path below is gated behind a prominent, one-time
disclosure in the app before the toggle takes effect (see
[android-app](android-app.md)).

This page is for the case that default doesn't cover: a host with **no local
network path to the phone at all** — a cloud-hosted AI session, most notably.

**Not sure which path you want?** Start with **Quick Tunnel**, right below.
It is the only one of these that needs nothing from you beyond pressing one
button in the app — no account, no API token, no relay to deploy, no address
to look up. Read further only if you specifically want something that
outlives one session (a stable address across restarts) or that doesn't
depend on Cloudflare at all.

## Quick Tunnel, for zero setup

Tap **"Start tunnel and send to your AI assistant"** on the "This phone" tab.
The app launches a bundled `cloudflared` binary in Cloudflare's anonymous
["Quick Tunnels"](https://developers.cloudflare.com/cloudflare-one/connections/connect-networks/do-more-with-tunnels/trycloudflare/)
mode — no Cloudflare account, no login, no API token — which hands back a
random `https://….trycloudflare.com` address that Cloudflare proxies straight
through to the phone for as long as the tunnel keeps running. The app then
opens a share sheet with a ready-to-paste connect command already filled in;
send it to yourself (Slack, notes, email — wherever you'll read it from the
machine running your AI host) and run it there:

```bash
MCPSERVED_MODE=tunnel MCPSERVED_HOST=https://random-words-here.trycloudflare.com \
mcpserved install claude-code
```

That's genuinely the whole setup. `MCPSERVED_HOST` here is a full URL, not a
bare address the way it is for the IPv6/UPnP paths below — `mcpserved` dials
it directly over WebSocket (see `mcp/src/tunnel-link.ts`), since Quick
Tunnels proxy HTTP/WebSocket traffic only, never a raw TCP socket the way
`adb forward` or a direct IPv6/UPnP dial does. That's also why this is a
*different* device-side server than the other four paths: `LocalWsServer`
(port 8795, loopback-only, reached only through this tunnel) runs the exact
same sealed-frame protocol as `LocalServer`, just wrapped in WebSocket frames
instead of raw bytes.

**The catch, and it's the only one:** the address is ephemeral. It stops
answering the moment the tunnel is stopped, the app is killed, or the phone
reboots — there's no "reconnect to the same URL later," a fresh tap gets a
fresh address. If you want something that survives across sessions, that's
what the four paths below are for — in particular, [deploying your own
relay](#running-a-relay) gets you a stable address for the same one-tap
convenience, at the cost of a one-time deploy step.

**This is not the same thing as `MCPSERVED_MODE=relay`, below**, even though
both eventually run through a Cloudflare-adjacent URL. A Quick Tunnel is a
bare reverse proxy with nothing brokering a connection on the other end — the
phone *is* the server, reached directly. `MCPSERVED_MODE=relay` instead dials
a relay server that pairs two separate connections (the phone and your host)
by a room token and forwards bytes between them. Pointing a `relay`-mode
command at a Quick Tunnel's URL, or vice versa, will simply fail to connect;
each mode's variables (`MCPSERVED_HOST` vs. `MCPSERVED_RELAY_URL` +
`MCPSERVED_RELAY_ROOM`) are not interchangeable.

## Four more paths

The obvious asks — "let me use Tailscale," "let me run my own relay so the
address doesn't change every time," "let me use my phone's own address," "let
me skip hosting anything and still be reachable" — turn out to be four more
engineering problems, once you separate them by which of the phone's two
servers they extend:

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
| **IPv6** | `LocalServer`'s sealed-frame protocol | "The phone should just host it" — no relay, no router cooperation, no third party of any kind: the phone's own global IPv6 address, direct. IPv6 has no NAT to traverse in the first place, so this is the only one of the four with a real (if not guaranteed) chance of working over cellular too |
| **UPnP** | `LocalServer`'s sealed-frame protocol | "Let me skip hosting anything and just be reachable" — no relay, no third party, works only on a Wi-Fi network whose router supports it |
| **Relay** | `LocalServer`'s sealed-frame protocol | "Let me run my own relay, for a stable address" — the same client/server code Quick Tunnel's `LocalWsServer` speaks, but reached through a room-pairing relay you deploy once rather than a fresh ephemeral tunnel every time; works from anywhere, including cellular. Deploying your own costs nothing and needs no server to maintain — see [Running a relay](#running-a-relay) below |

`McpServer` never gets an IPv6, UPnP, or relay path. Exposing its plaintext-
behind-a-token protocol straight to the internet, by any of the three
mechanisms, would hand whoever finds it your taps, typed text, and shell
output in the clear — the opposite of what any of them should buy you. If you
want to be reachable directly or via a relay, it carries the already-encrypted
protocol; if you want a wider bind, pair it with a private mesh you trust.

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

## Path 2: IPv6, the most direct path there is

Turn on **Listen on this phone's IPv6 address** on the same screen. IPv6 has
no NAT to traverse in the first place — no router to ask, no relay to run, no
third party in the data path at all — so this is literally the phone hosting
itself: `LocalServer` binds the device's own current global IPv6 address, the
screen shows it, and it's rechecked every few minutes since the address itself
can rotate (Android's RFC 4941 privacy addresses) or change outright on a
network switch.

The catch is the one thing that was never in the phone's control to begin
with: whether the network's firewall allows unsolicited inbound to that
address. Most home routers pass IPv6 straight through by default (no NAT
means no equivalent of port forwarding to configure), which is why this often
just works on Wi-Fi with nothing to turn on at the router. Cellular is
genuinely different from the UPnP story below: a carrier's IPv6 allocation is
real and globally routable — there is no cellular-side NAT for IPv6 the way
there is for IPv4 — but many carriers still run a stateful firewall that
blocks inbound regardless. That makes this the only one of the four paths
with an actual chance on cellular, not a guarantee of one.

On the host side, point `mcpserved` at the address the app shows with
`MCPSERVED_MODE=app` and `MCPSERVED_HOST=<that address>` (an IPv6 literal
needs no bracket notation here — it's a bare address, not a URL). This skips
`adb forward` and dials the address directly:

```bash
MCPSERVED_MODE=app MCPSERVED_HOST=2001:db8::1234:5678 \
mcpserved install claude-code
```

## Path 3: UPnP, for zero hosting

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
DHCP churn.

Point a host at it the same way as the IPv6 path above —
`MCPSERVED_MODE=app MCPSERVED_HOST=<mapped address>` — since both land on the
same `LocalServer` port and the same direct-dial mechanism in `AppLink`; only
how the address was obtained differs.

## Path 4: relay, for everything else

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

The relay itself is a small, deliberately dumb WebSocket server: it pairs one
"device" connection and one "host" connection by room token and forwards
bytes verbatim, never parsing or logging a payload. Two implementations of
the identical protocol, pick one:

- **[`relay/cloudflare/`](../../relay/cloudflare/README.md) — no server to
  run, free at this scale.** A Cloudflare Worker + Durable Object. One
  `wrangler deploy` and it's live, with TLS already handled by Cloudflare —
  nothing to keep running, nothing to pay for beyond Cloudflare's free tier.
  This is the answer to "isn't there a way to do this without hosting or
  paying for a server" — there is, and it's this: you deploy your own, to
  your own account, for free, and MCPserved never has to operate a shared
  one for anybody.
- **[`relay/`](../../relay/README.md) — self-hosted Node.** `npm start`
  locally, a Docker container, or on a VPS you already run, fronted by
  Cloudflare Tunnel or ngrok for a public HTTPS URL with automatic TLS if you
  don't want to add a Cloudflare account for the Worker option above.

Because either relay only ever forwards already-sealed ciphertext, whichever
one you pick exposes its operator to opaque bytes, not to your phone's screen
contents or typed input.

**Whose relay should you use?** One you run yourself is the safest bet — you
already trust your own infrastructure, and the Cloudflare Worker option above
makes "run yourself" cost nothing and need no maintenance, so there's rarely
a reason to reach for someone else's. A relay run by someone else can occupy
your device's room slot with a bogus connection (denial of service on that
room; the fix is rotating the room token) or watch ciphertext go by, but
cannot decrypt it and cannot impersonate either end without the X25519 pairing
secret, which never travels anywhere near the relay.

## What none of the four paths change

All four are new *transports*. None is a new *authority* — every request that
arrives over any of them still passes through the same `Enforcer.guard`
bracketing, the same per-package grant table, and the same session gate as a
request arriving over `adb forward`. See [security](security.md)'s "Trust
boundaries" table: the relay is untrusted by construction, exactly like the
desktop MCP server always was, because it sits downstream of a language
model's output and carries frames without deciding anything. A live IPv6
listener or UPnP mapping is untrusted the same way, just without an
intermediary: either makes the already-authenticated port reachable by more
people, not by anyone with more authority once they get there.
