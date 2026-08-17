# MCPserved relay — Cloudflare Worker

Same relay, no server to run. `relay/` (the sibling directory) is a Node
process someone has to keep alive; this is a Cloudflare Worker + Durable
Object instead, deployed once with `wrangler deploy` and then just... there.
No VPS, no container, no systemd unit, no bill from anyone but Cloudflare —
and Cloudflare's free tier covers this at the scale MCPserved actually uses
it at (a couple of long-lived WebSocket connections, not public traffic).
Check current [Cloudflare Workers pricing](https://developers.cloudflare.com/workers/platform/pricing/)
if that matters to you; limits and tiers change.

Nobody involved in MCPserved hosts this for you, and this repo doesn't ship
a default relay URL baked into the app — you deploy your own, to your own
Cloudflare account, and it's yours. That's deliberate: "who hosts the relay"
stops being a question the project has to answer when the honest answer is
"you do, in one command, for free."

## Wire-compatible, not a separate feature

This Worker speaks the exact same protocol as `relay/`'s Node server:
`GET /connect?room=<token>&role=device|host` upgrades to a WebSocket, pairs
exactly one `device` and one `host` per room, and forwards bytes verbatim —
never parsed, never logged, never decrypted, because they're already sealed
under MCPserved's device-pairing key before they reach either relay. Every
existing client (`RemoteRelayClient.kt` on the phone, `relay-link.ts` in the
desktop MCP server, the desktop bridge's `Target.relay`) builds that same
URL. Point any of them at your deployed Worker instead of a self-hosted Node
instance and it works with **no client code change** — only the URL differs.

## Deploy

```sh
cd relay/cloudflare
npm install -g wrangler        # or: npx wrangler ...
wrangler deploy                # creates the Worker + Durable Object
```

Copy the deployed URL, e.g. `https://mcpserved-relay.<you>.workers.dev` —
use the `wss://` scheme when you configure it below; Cloudflare answers both.

## Configure

**On the phone:** Remote Access tab → **Dial out to a relay** → paste the
Worker URL into the Relay URL field (no trailing `/connect`, the client
appends that itself), same as pointing it at a self-hosted Node relay.

**On the host** (`mcpserved`'s stdio server):

```bash
MCPSERVED_MODE=relay \
MCPSERVED_RELAY_URL=wss://mcpserved-relay.<you>.workers.dev \
MCPSERVED_RELAY_ROOM=<room token from the app> \
mcpserved install claude-code
```

See [docs/guide/remote-access.md](../../docs/guide/remote-access.md) for the
full relay path (both hosting options) and
[docs/guide/security.md](../../docs/guide/security.md) for what a relay
operator — Cloudflare included — can and can't see.

## What's deliberately different from the Node relay

- **No room cap, no connection cap.** The Node server bounds an in-memory
  `Map` because one process holds every room; a Durable Object is one room,
  and Cloudflare shards and bills those independently, so there's no
  equivalent "too many rooms" ceiling to enforce here.
- **No custom rate limiter.** Cloudflare's edge already absorbs abusive
  traffic before it reaches a Worker at a scale this project's own
  hand-rolled limiter never could; layering another one on top would be
  redundant, not additive.
- **Same room-token threat model as the Node relay.** Nothing here checks a
  separate access key — the room token is the only thing gating who can
  occupy a room, exactly like the Node option. Whoever holds a room token can
  occupy a slot with a bogus connection (denial of service on that room, not
  a way to read or forge sealed frames); the fix, same as always, is
  rotating the token from the app's Remote Access screen.
