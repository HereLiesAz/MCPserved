# mcpserved-relay

A blind byte-pipe relay for MCPserved's opt-in remote-access path. It pairs
one device connection with one host connection by a shared room token, then
forwards bytes between them — nothing more.

**It never decrypts anything.** The bytes it forwards are already sealed
under the device's X25519 pairing secret (the same protocol
`LocalServer`/`FrameSession` speak locally), so whoever runs this relay —
you, on your own VPS, or a third party fronting it with Cloudflare Tunnel or
ngrok for a free public HTTPS URL — sees only opaque ciphertext. It does not
log payloads, does not persist rooms to disk, and forgets a room the moment
both sides disconnect.

This is *not* required for MCPserved's default local operation (adb-forward
or LAN). It exists only for the opt-in remote-access path — see
`docs/guide/remote-access.md` in the repo root for when and why you'd deploy
one.

## Running it

```bash
npm install
npm run build
RELAY_PORT=8787 node dist/server.js
```

Or with Docker:

```bash
docker build -t mcpserved-relay .
docker run -p 8787:8787 -e RELAY_IDLE_TIMEOUT_MS=600000 mcpserved-relay
```

### Environment variables

| Variable | Default | Meaning |
| --- | --- | --- |
| `RELAY_PORT` | `8787` | Port the relay listens on. |
| `RELAY_IDLE_TIMEOUT_MS` | `600000` (10 min) | A room with no traffic for this long is closed and forgotten. |
| `RELAY_MAX_ROOMS` | `2000` | Concurrent rooms this instance holds. A *new* room beyond this is refused (code `4010`); completing an existing room's pairing never is. |
| `RELAY_MAX_CONNECTIONS` | `4000` | Concurrent WebSocket connections across all rooms. Beyond it, new upgrade attempts are refused at the TCP level. |
| `RELAY_MAX_PAYLOAD_BYTES` | `8388608` (8 MiB) | Largest single WebSocket frame accepted. Bounds memory one connection can force per message. |
| `RELAY_RATE_LIMIT_PER_MINUTE` | `60` | New connection attempts allowed per remote address per minute, sliding window. Keyed on the immediate TCP peer, not `X-Forwarded-For` (spoofable) — fronted by a reverse proxy, this becomes a shared budget across everyone behind it rather than a true per-attacker limit; that's a deliberate, conservative tradeoff, not an oversight.

These defaults are sane for a small, personal relay. **If you intend to expose this publicly** (a shared/default relay other people's phones dial into, not just your own), lower `RELAY_MAX_ROOMS`/`RELAY_MAX_CONNECTIONS` to something proportional to the capacity you're actually paying for, and put real monitoring in front of it — the relay bounds *memory*, it does not bound *cost* or alert you to sustained abuse on its own.

## Getting a public HTTPS URL

The relay itself speaks plain `ws://` — deploying it behind a TLS-terminating
front end is the supported path, not something this project builds:

- **Cloudflare Tunnel**: `cloudflared tunnel --url http://localhost:8787`
  gives you a public `https://…trycloudflare.com` URL with zero DNS/cert
  setup. Use the `wss://` form of that URL when configuring the device and
  host.
- **ngrok**: `ngrok http 8787` does the same.
- **Your own reverse proxy** (Caddy, nginx, Traefik) in front of a relay
  deployed on a VPS you control, if you'd rather not depend on a third party
  at all.

Any of these are equally valid — since the relay is blind either way, the
choice is about convenience and who you trust to keep the endpoint up, not
about what the relay can see.

## Protocol

`GET /connect?room=<token>&role=device|host`, upgraded to a WebSocket. Two
connections in the same room (one `device`, one `host`) get paired; every
message one sends is forwarded verbatim to the other. A second connection
claiming an already-occupied role is closed with code `4009` rather than
displacing the existing one — guessing or leaking a room token lets a third
party deny service to a room, never impersonate a peer, since sealed frames
still require the pairing secret the relay never sees.

## Development

```bash
npm test
```

`test/rooms.test.ts` exercises the pairing/eviction logic directly with fake
sockets and a fake clock; `test/server.test.ts` drives the same logic through
a real WebSocket server and real `ws` clients on localhost.
