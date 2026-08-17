/**
 * MCPserved relay, as a Cloudflare Worker + Durable Object.
 *
 * The wire protocol is identical to `relay/src/server.ts` (the self-hosted
 * Node option): `GET /connect?room=<token>&role=device|host` upgrades to a
 * WebSocket, a room pairs exactly one `device` and one `host`, and bytes are
 * forwarded verbatim — never parsed, never logged, never decrypted. Every
 * existing client (`RemoteRelayClient.kt`, `relay-link.ts`, the desktop
 * bridge's `Target.relay`) builds that same URL, so pointing any of them at
 * a deployed Worker instead of a self-hosted Node process needs no client
 * code change at all — see `docs/guide/remote-access.md`.
 *
 * Why this exists alongside the Node option: `relay/`'s Node server is
 * correct but needs a machine to keep running — a VPS, a container host,
 * something with a bill and a systemd unit. A Cloudflare Worker needs none
 * of that. `wrangler deploy` publishes it to Cloudflare's edge, and it costs
 * nothing to run at MCPserved's traffic scale (a handful of long-lived
 * WebSocket connections, not a public service) — Cloudflare's free tier
 * covers Workers requests and a Durable Object allocation generous enough
 * for personal use; check current Cloudflare Workers pricing if that
 * matters to you, since limits and tiers do change. Either way, there is
 * nothing here for the MCPserved project itself to host, pay for, or
 * operate — each operator deploys their own, to their own account, in one
 * command, same as Guillotine's `tools/mcp-relay`.
 *
 * One Durable Object instance = one room, addressed by `idFromName(room)`.
 * Cloudflare shards and scales that automatically, so unlike the Node
 * server's in-memory `Map` there is no meaningful "too many rooms" cap to
 * enforce here — a room that's never dialed just never gets an instance.
 * What *does* still matter, and is preserved: a role slot already held by a
 * live socket is never displaced by a new claimant, only rejected — that
 * invariant is what stops a token-guesser from bumping the legitimate peer
 * off a room it merely knows the name of.
 */

export class RelayRoom {
  constructor(state, env) {
    this.state = state;
    this.env = env;
    this.device = null;
    this.host = null;
  }

  async fetch(request) {
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }
    const role = new URL(request.url).searchParams.get("role");
    if (role !== "device" && role !== "host") {
      return new Response("role must be device or host", { status: 400 });
    }
    if (this[role]) {
      // Reject, don't displace — the caller closes; nothing here does it for
      // them, since who owns delivering the reason is the caller's transport.
      return new Response("room role already occupied", { status: 409 });
    }

    const pair = new WebSocketPair();
    const [client, server] = [pair[0], pair[1]];
    server.accept();
    this[role] = server;

    server.addEventListener("message", (event) => {
      const other = role === "device" ? this.host : this.device;
      if (other) {
        try {
          other.send(event.data);
        } catch (_) {
          // Peer socket is mid-teardown; its own close handler will run.
        }
      }
    });

    const cleanup = () => {
      if (this[role] === server) this[role] = null;
    };
    server.addEventListener("close", cleanup);
    server.addEventListener("error", cleanup);

    return new Response(null, { status: 101, webSocket: client });
  }
}

export default {
  async fetch(request, env) {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return new Response("mcpserved relay (cloudflare): blind byte pipe, nothing to see here\n");
    }
    if (url.pathname !== "/connect") {
      return new Response("not found", { status: 404 });
    }
    if (request.headers.get("Upgrade") !== "websocket") {
      return new Response("expected websocket", { status: 426 });
    }

    const room = url.searchParams.get("room");
    if (!room) return new Response("missing room", { status: 400 });

    const id = env.RELAY_ROOM.idFromName(room);
    return env.RELAY_ROOM.get(id).fetch(request);
  },
};
