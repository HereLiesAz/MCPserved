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

Tap **Set up remote access** on the "This phone" tab. The app starts an
anonymous Cloudflare Quick Tunnel that forwards HTTPS requests to the phone's
bearer-authenticated `McpServer` on port 8791. The share sheet supplies the
public `https://…trycloudflare.com/mcp` URL and its Authorization bearer
header. Register **that HTTP MCP endpoint directly** with your host; it needs
neither desktop `mcpserved` nor X25519 pairing.

For Claude Code, use the command supplied by the app:

```bash
claude mcp add --transport http mcpserved https://YOUR-QUICK-TUNNEL.trycloudflare.com/mcp \
  --header "Authorization: Bearer YOUR-DEVICE-TOKEN"
```

The old desktop `MCPSERVED_MODE=tunnel` expected a sealed-frame WebSocket.
It is **not compatible** with the current HTTP tunnel and now fails explicitly
instead of attempting the wrong protocol.

**Security:** TLS terminates at Cloudflare; unlike the sealed-frame bridge
and relay, this HTTP mode is *not* end-to-end encrypted from client to device.
Cloudflare can inspect the HTTP requests and bearer credential. Anyone who
possesses the bearer can request the same device actions the on-device MCP
endpoint allows. Rotate the bearer in the app when access should end.
The URL is ephemeral and disappears when you stop the tunnel or disarm the app.

### Named Cloudflare tunnel: stable public address

The stable-address control provisions a tunnel and proxied DNS CNAME in a
Cloudflare zone you own. It exposes the **same HTTP MCP server** and has the
same bearer-token and Cloudflare TLS-termination considerations as a Quick
Tunnel. It needs **Account → Cloudflare Tunnel:Edit, Zone → DNS:Edit, and
Zone:Read** scoped to the correct account/zone. It refuses to overwrite
unrelated DNS records or reuse a run token for a different hostname.
The run token is a secret; to revoke it remotely, rotate or delete the tunnel
in Cloudflare as well as clearing local credentials.

## Four more paths

The obvious asks — "let me use Tailscale," "let me run my own relay so the
address doesn't change every time," "let me use my phone's own address," "let
me skip hosting anything and still be reachable" — turn out to be four more
engineering problems, once you separate them by which of the phone's two
servers they extend:

- **`McpServer`** (MCP-over-HTTP, port 8791) authenticates with a bearer token.
  Plain HTTP is safe only on loopback or inside a trusted encrypted tunnel/mesh;
  Cloudflare Quick/Named Tunnels expose it over public HTTPS with TLS terminated
  at Cloudflare, not end-to-end ciphertext.
- **`LocalServer`** (the sealed-frame protocol the desktop bridge and the app
  backend speak, port 8790) is **already end-to-end encrypted** — X25519
  pairing, per-connection ChaCha20-Poly1305 frames — regardless of transport.

So:

| Path | Extends | Serves |
| --- | --- | --- |
| **Wider bind** | `McpServer` | "Let me use Tailscale/WireGuard" |
| **IPv6** | `LocalServer`'s sealed-frame protocol | "The phone should just host it" — no relay, no router cooperation, no third party of any kind: the phone's own global IPv6 address, direct. IPv6 has no NAT to traverse in the first place, so this is the only one of the four with a real (if not guaranteed) chance of working over cellular too |
| **UPnP** | `LocalServer`'s sealed-frame protocol | "Let me skip hosting anything and just be reachable" — no relay, no third party, works only on a Wi-Fi network whose router supports it |
| **Relay** | `LocalServer`'s sealed-frame protocol | Stable address through a room-pairing relay; encrypted end-to-end, unlike the Cloudflare HTTP tunnels above. See [Running a relay](#running-a-relay) below. |

IPv6, UPnP, and the sealed-frame relay use `LocalServer`. Cloudflare Quick
and Named Tunnels are separate paths to `McpServer` over public HTTPS. TLS
ends at Cloudflare, which can see requests and the bearer token. Direct MCP
wildcard binding is only appropriate behind a trusted private mesh.

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

## Authorization

The paired-app dispatcher still enforces session and package grants for both
sealed-frame paths and the HTTP MCP endpoint. Raw `MCPSERVED_MODE=adb`
is explicitly outside that grant model and holds device-wide shell authority.
A public Cloudflare HTTP tunnel protects access with the bearer token, not
X25519 sealed frames. Cloudflare is therefore a trusted intermediary for those
HTTP paths, unlike the blind sealed-frame relay.
